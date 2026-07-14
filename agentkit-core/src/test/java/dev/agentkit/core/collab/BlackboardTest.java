package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class BlackboardTest {

    @Test
    void assignsMonotonicIdsAndPreservesOrder() {
        Blackboard board = new Blackboard();
        Blackboard.Entry a = board.post("alice", "plan", "first");
        Blackboard.Entry b = board.post("bob", "plan", "second");

        assertThat(a.id()).isEqualTo(1);
        assertThat(b.id()).isEqualTo(2);
        assertThat(board.entries()).containsExactly(a, b);
        assertThat(board.size()).isEqualTo(2);
    }

    @Test
    void filtersByTopicCaseInsensitively() {
        Blackboard board = new Blackboard();
        board.post("alice", "Research", "r1");
        board.post("bob", "writing", "w1");
        board.post("carol", "research", "r2");

        assertThat(board.byTopic("research")).extracting(Blackboard.Entry::content)
                .containsExactly("r1", "r2");
        assertThat(board.byTopic("WRITING")).hasSize(1);
        assertThat(board.byTopic("absent")).isEmpty();
    }

    @Test
    void sinceReturnsOnlyEntriesAfterTheGivenId() {
        Blackboard board = new Blackboard();
        board.post("a", "t", "1");
        Blackboard.Entry second = board.post("a", "t", "2");
        board.post("a", "t", "3");

        assertThat(board.since(second.id())).extracting(Blackboard.Entry::content)
                .containsExactly("3");
        assertThat(board.since(0)).hasSize(3);
    }

    @Test
    void concurrentPostsAllLandWithUniqueIds() throws InterruptedException {
        Blackboard board = new Blackboard();
        int threads = 8;
        int perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String author = "w" + t;
            Thread worker = new Thread(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    ids.add(board.post(author, "topic", "n").id());
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(board.size()).isEqualTo(threads * perThread);
        assertThat(ids).hasSize(threads * perThread); // no duplicate ids handed out

        // The list order never diverges from id order, even under concurrent posts:
        // id assignment and insertion are atomic, so entries() is a contiguous,
        // id-sorted prefix. (Guards the reserve-id-then-append race.)
        List<Long> ordered = board.entries().stream().map(Blackboard.Entry::id).toList();
        assertThat(ordered).isSorted();
        assertThat(ordered).containsExactlyElementsOf(
                LongStream.rangeClosed(1, threads * perThread).boxed().toList());

        // A reader paging forward by max-seen id recovers every entry, no gaps.
        List<Long> paged = new ArrayList<>();
        long cursor = 0;
        for (List<Blackboard.Entry> batch = board.since(cursor); !batch.isEmpty();
                batch = board.since(cursor)) {
            batch.forEach(e -> paged.add(e.id()));
            cursor = batch.get(batch.size() - 1).id();
        }
        assertThat(paged).containsExactlyElementsOf(
                LongStream.rangeClosed(1, threads * perThread).boxed().toList());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

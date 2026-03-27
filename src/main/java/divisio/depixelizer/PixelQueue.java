package divisio.depixelizer;

import java.util.Arrays;

public class PixelQueue {
    private final boolean[] queued;
    private final int[] queue;
    private int front = 0;
    private int back = 0;

    public PixelQueue(final int size) {
        this.queued = new boolean[size];
        this.queue = new int[size];
    }

    public void reset() {
        Arrays.fill(queued, false);
        front = 0;
        back = 0;
    }

    public boolean isEmpty() {
        return front == back;
    }

    public void enqueue(final int i) {
        //every int can only be enqueued once
        if (queued[i]) return;
        queue[back++] = i;
        queued[i] = true;
    }

    public int dequeue() {
        return queue[front++];
        //we do *not*(!) set queued[i] = false on purpose!
        //this queue is for a BFS and  we only want to visit each pixel once.
    }
}

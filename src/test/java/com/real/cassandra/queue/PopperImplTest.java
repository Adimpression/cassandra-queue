package com.real.cassandra.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.cassandra.utils.UUIDGen;
import org.junit.Before;
import org.junit.Test;

import com.real.cassandra.queue.pipes.PipeDescriptorFactory;
import com.real.cassandra.queue.pipes.PipeDescriptorImpl;
import com.real.cassandra.queue.pipes.PipeLockerImpl;
import com.real.cassandra.queue.utils.MyIp;

public class PopperImplTest extends CassQueueTestBase {
    private CassQueueFactoryImpl cqFactory;

    @Test
    public void testSinglePopper() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PusherImpl pusher = cq.createPusher();
        PopperImpl popper = cq.createPopper(false);

        String msgData = "the data-" + System.currentTimeMillis();
        CassQMsg qMsgPush = pusher.push(msgData);
        popper.forceRefresh();
        CassQMsg qMsgPop = popper.pop();

        assertEquals("msg pushed isn't msg popped", qMsgPush, qMsgPop);
        assertEquals("msg pushed has different value than msg popped", qMsgPush.getMsgData(), qMsgPop.getMsgData());

        List<PipeDescriptorImpl> pipeDescList = qRepos.getOldestNonEmptyPipes(cq.getName(), 1);
        PipeDescriptorImpl pipeDesc = pipeDescList.get(0);

        assertNull("msg should have been moved from waiting to delivered", qRepos.getOldestMsgFromWaitingPipe(pipeDesc));
        CassQMsg qDelivMsg = qRepos.getOldestMsgFromDeliveredPipe(pipeDesc);
        assertEquals("msg pushed isn't msg in delivered pipe", qMsgPush, qDelivMsg);
        assertEquals("msg pushed has different value than msg in delivered pipe", qMsgPush.getMsgData(),
                qDelivMsg.getMsgData());
    }

    @Test
    public void testMultiplePoppersSinglePipeNotThreaded() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PusherImpl pusher = cq.createPusher();
        PopperImpl[] popperArr = new PopperImpl[] {
                cq.createPopper(false), cq.createPopper(false), cq.createPopper(false) };
        int msgCount = 6;
        ArrayList<CassQMsg> msgList = new ArrayList<CassQMsg>(msgCount);

        for (int i = 0; i < msgCount; i++) {
            msgList.add(pusher.push("data-" + System.currentTimeMillis() + "-" + i));
        }

        for (PopperImpl popper : popperArr) {
            popper.forceRefresh();
        }

        for (int i = 0; i < msgCount; i++) {
            CassQMsg qPushMsg = msgList.get(i);
            CassQMsg qPopMsg = popperArr[i % popperArr.length].pop();
            assertEquals("pushed msg not same as popped", qPushMsg, qPopMsg);
            assertEquals("pushed msg data not same as popped data", qPushMsg.getMsgData(), qPopMsg.getMsgData());
        }

        List<PipeDescriptorImpl> pipeDescList = qRepos.getOldestNonEmptyPipes(cq.getName(), 1);
        PipeDescriptorImpl pipeDesc = pipeDescList.get(0);

        assertNull("all msgs should have been moved from waiting to delivered",
                qRepos.getOldestMsgFromWaitingPipe(pipeDesc));

        for (int i = 0; i < msgCount; i++) {
            CassQMsg qPushMsg = msgList.get(i);
            CassQMsg qDelivMsg = qRepos.getOldestMsgFromDeliveredPipe(pipeDesc);
            qRepos.removeMsgFromPendingPipe(qDelivMsg);
            assertEquals("pushed msg not same as popped", qPushMsg, qDelivMsg);
            assertEquals("pushed msg data not same as popped data", qPushMsg.getMsgData(), qDelivMsg.getMsgData());
        }

    }

    @Test
    public void testSinglePusherSinglePopperMultiplePipes() throws Exception {
        int maxPushesPerPipe = 5;
        int popWidth = 3;
        int numIterations = 3;

        CassQueueImpl cq =
                cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, maxPushesPerPipe, popWidth, 5000,
                        false);
        PusherImpl pusher = cq.createPusher();
        PopperImpl popper = cq.createPopper(false);

        CassQMsg[][][] msgArr = new CassQMsg[numIterations][popWidth][maxPushesPerPipe];

        for (int iter = 0; iter < numIterations; iter++) {
            for (int pipe = 0; pipe < popWidth; pipe++) {
                for (int push = 0; push < maxPushesPerPipe; push++) {
                    msgArr[iter][pipe][push] =
                            pusher.push("data-" + System.currentTimeMillis() + "-" + iter + "." + pipe + "." + push);
                }
            }
        }

        for (int iter = 0; iter < numIterations; iter++) {
            for (int push = 0; push < maxPushesPerPipe; push++) {
                for (int pipe = 0; pipe < popWidth; pipe++) {
                    CassQMsg qPushMsg = msgArr[iter][pipe][push];
                    CassQMsg qPopMsg = popper.pop();
                    assertEquals("pushed msg data not same as popped data", qPushMsg.getMsgData(), qPopMsg.getMsgData());
                }
            }

            // since our data is crafted nicely, we do one pop between
            // interations to reload popper's list of pipe descriptors. this
            // works because all pipes in the popper's view of the world were
            // completely exhausted.
            // assertNull(popper.pop());
        }
    }

    @Test
    public void testMarkingPipeAsFinishedEmpty() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PusherImpl pusher = cq.createPusher();
        PopperImpl popper = cq.createPopper(false);
        int msgCount = 10;

        for (int i = 0; i < msgCount; i++) {
            pusher.push("data-" + System.currentTimeMillis() + "-" + i);
        }
        pusher.push("over-to-next");
        List<PipeDescriptorImpl> pipeDescList = qRepos.getOldestNonEmptyPipes(cq.getName(), 1);
        PipeDescriptorImpl pipeDesc = pipeDescList.get(0);
        qRepos.setPipeDescriptorStatus(pipeDesc, PipeDescriptorImpl.STATUS_PUSH_FINISHED);

        for (int i = 0; i < msgCount; i++) {
            popper.pop();
            pipeDesc = qRepos.getPipeDescriptor(cq.getName(), pipeDesc.getPipeId());
            assertEquals("pipe should still be " + PipeDescriptorImpl.STATUS_PUSH_FINISHED,
                    PipeDescriptorImpl.STATUS_PUSH_FINISHED, pipeDesc.getStatus());
        }

        // this pop will return null, but will force marking of pipe as
        // "finished empty"
        // assertNull(popper.pop());

        // do one more pop to push over the edge to next pipe
        // popper.forceRefresh();
        assertEquals("should have rolled to next pipe and retrieved next msg", "over-to-next", popper.pop()
                .getMsgData());

        pipeDesc = qRepos.getPipeDescriptor(cq.getName(), pipeDesc.getPipeId());
        assertEquals("pipe should now be " + PipeDescriptorImpl.STATUS_FINISHED_AND_EMPTY,
                PipeDescriptorImpl.STATUS_FINISHED_AND_EMPTY, pipeDesc.getStatus());

    }

    @Test
    public void testNoPipes() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PopperImpl popper = cq.createPopper(false);
        assertNull("should return null", popper.pop());
    }

    @Test
    public void testShutdownInProgress() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PopperImpl popper = cq.createPopper(false);
        PusherImpl pusher = cq.createPusher();

        pusher.push("blah1");
        pusher.push("blah1");
        pusher.push("blah1");

        popper.forceRefresh();
        popper.pop();
        popper.shutdown();

        try {
            popper.pop();
            fail("pusher should not have allowed a new message since in shutdown mode");
        }
        catch (IllegalStateException e) {
            // all good, expected
        }
    }

    @Test
    public void testCommit() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PopperImpl popper = cq.createPopper(false);
        PusherImpl pusher = cq.createPusher();
        int numMsgs = 3;
        ArrayList<CassQMsg> msgList = new ArrayList<CassQMsg>(numMsgs);

        for (int i = 0; i < numMsgs; i++) {
            pusher.push("blah");
            msgList.add(popper.pop());
        }

        popper.forceRefresh();
        for (int i = numMsgs; 0 < i; i--) {
            List<PipeDescriptorImpl> pdList = qRepos.getOldestNonEmptyPipes(cq.getName(), numMsgs * 2);
            PipeDescriptorImpl pipeDesc = pdList.get(0);
            assertEquals("as messages are commited, should be removed from delivered pipe", i, qRepos
                    .getDeliveredMessagesFromPipe(pipeDesc, numMsgs * 2).size());
            popper.commit(msgList.remove(0));
        }

        List<PipeDescriptorImpl> pdList = qRepos.getOldestNonEmptyPipes(cq.getName(), numMsgs * 2);
        PipeDescriptorImpl pipeDesc = pdList.get(0);
        assertEquals("commiting is complete, shouldn't be any messages in delivered pipe", 0, qRepos
                .getDeliveredMessagesFromPipe(pipeDesc, numMsgs * 2).size());
        assertEquals("commiting is complete, shouldn't be any messages in waiting pipe", 0, qRepos
                .getWaitingMessagesFromPipe(pipeDesc, numMsgs * 2).size());
    }

    @Test
    public void testRollback() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        PopperImpl popper = cq.createPopper(false);
        PusherImpl pusher = cq.createPusher();
        int numMsgs = 6;
        ArrayList<CassQMsg> msgList = new ArrayList<CassQMsg>(numMsgs);

        for (int i = 0; i < numMsgs; i++) {
            pusher.push("blah");
            msgList.add(popper.pop());
        }

        ArrayList<CassQMsg> rollbackList = new ArrayList<CassQMsg>(numMsgs / 2);
        popper.forceRefresh();
        for (int i = numMsgs; 0 < i; i--) {
            List<PipeDescriptorImpl> pdList = qRepos.getOldestNonEmptyPipes(cq.getName(), numMsgs * 2);
            PipeDescriptorImpl pipeDesc = pdList.get(0);
            assertEquals("as messages are commited, should be removed from delivered pipe", i, qRepos
                    .getDeliveredMessagesFromPipe(pipeDesc, numMsgs * 2).size());
            if (0 == i % 2) {
                popper.commit(msgList.remove(0));
            }
            else {
                rollbackList.add(popper.rollback(msgList.remove(0)));
            }
        }

        List<PipeDescriptorImpl> pdList = qRepos.getOldestNonEmptyPipes(cq.getName(), numMsgs * 2);
        assertEquals("should now be an extra pipe with rollbacks", 2, pdList.size());
        assertEquals("commiting/rollback is complete, shouldn't be any messages in delivered pipe", 0, qRepos
                .getDeliveredMessagesFromPipe(pdList.get(0), numMsgs * 2).size());
        assertEquals("commiting/rollback is complete, should be " + numMsgs / 2 + "messages in waiting pipe",
                numMsgs / 2, qRepos.getWaitingMessagesFromPipe(pdList.get(1), numMsgs * 2).size());

        cq.setMaxPopWidth(2);
        popper.forceRefresh();
        for (int i = 0; i < numMsgs / 2; i++) {
            assertEquals(rollbackList.remove(0), popper.pop());
        }
    }

    @Test
    public void testPipeDescriptorExpires() throws Exception {
        CassQueueImpl cq = cqFactory.createInstance("test_" + System.currentTimeMillis(), 20000, 10, 1, 5000, false);
        UUID pipeId1 = UUIDGen.makeType1UUIDFromHost(MyIp.get());
        UUID pipeId2 = UUIDGen.makeType1UUIDFromHost(MyIp.get());
        qRepos.createPipeDescriptor(cq.getName(), pipeId1, PipeDescriptorImpl.STATUS_PUSH_ACTIVE, 1);
        qRepos.createPipeDescriptor(cq.getName(), pipeId2, PipeDescriptorImpl.STATUS_PUSH_ACTIVE,
                System.currentTimeMillis());
        PopperImpl popper = cq.createPopper(false);
        popper.pop();

        assertEquals(PipeDescriptorImpl.STATUS_FINISHED_AND_EMPTY, qRepos.getPipeDescriptor(cq.getName(), pipeId1)
                .getStatus());
        assertEquals(PipeDescriptorImpl.STATUS_PUSH_ACTIVE, qRepos.getPipeDescriptor(cq.getName(), pipeId2).getStatus());
    }

    // -------------------------

    @Before
    public void setupTest() throws Exception {
        cqFactory = new CassQueueFactoryImpl(qRepos, new PipeDescriptorFactory(qRepos), new PipeLockerImpl());
    }
}
package org.cryptocoinpartners.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.cryptocoinpartners.module.Context;
import org.cryptocoinpartners.schema.Book;
import org.cryptocoinpartners.schema.Event;
import org.cryptocoinpartners.schema.RemoteEvent;
import org.cryptocoinpartners.schema.Trade;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.Interval;

import com.espertech.esper.client.EPRuntime;

/**
 Manages a Context into which Trades and Books from the database are replayed.  The Context time is also managed by this
 class as it advances through the events.
 */
public class Replay {

    public static Replay all(boolean orderByTimeReceived) {
        return during(new Interval(getEventsStart(orderByTimeReceived), getEventsEnd(orderByTimeReceived)), orderByTimeReceived);
    }

    public static Replay since(Instant start, boolean orderByTimeReceived) {
        return during(new Interval(start, getEventsEnd(orderByTimeReceived)), orderByTimeReceived);
    }

    public static Replay until(Instant end, boolean orderByTimeReceived) {
        return during(new Interval(getEventsStart(orderByTimeReceived), end), orderByTimeReceived);
    }

    public static Replay between(Instant start, Instant end, boolean orderByTimeReceived) {
        return during(new Interval(start, end), orderByTimeReceived);
    }

    public static Replay during(Interval interval, boolean orderByTimeReceived) {
        return new Replay(interval, orderByTimeReceived);
    }

    public Replay(Interval replayTimeInterval, boolean orderByTimeReceived) {
        this.replayTimeInterval = replayTimeInterval; // set this before creating EventTimeManager
        this.context = Context.create(new EventTimeManager());
        this.orderByTimeReceived = orderByTimeReceived;
    }

    public Context getContext() {
        return context;
    }

    /**
     queries the database for all Books and Trades which have start <= time <= stop, then publishes those
     Events in order of time to this Replay's Context
     */

    public void run() {
        final Instant start = replayTimeInterval.getStart().toInstant();
        final Instant end = replayTimeInterval.getEnd().toInstant();
        service = Executors.newFixedThreadPool(1);
        //  Replay replayThread = new Replay();

        if (replayTimeInterval.toDuration().isLongerThan(timeStep)) {
            for (Instant now = start; !now.isAfter(end);) {
                final Instant stepEnd = now.plus(timeStep);
                ReplayStepRunnable replayStep = new ReplayStepRunnable(now, stepEnd, context.getRunTime());
                service.submit(replayStep);
                now = stepEnd;
            }
        } else
            replayStep(start, end);
    }

    private class ReplayStepRunnable implements Runnable {

        private final Instant start;
        private final Instant stop;
        private final EPRuntime runtime;

        public ReplayStepRunnable(Instant start, Instant stop, EPRuntime runtime) {
            this.start = start;
            this.stop = stop;
            this.runtime = runtime;

        }

        @Override
        public void run() {
            Iterator<RemoteEvent> ite = queryEvents(start, stop).iterator();
            while (ite.hasNext()) {
                RemoteEvent event = ite.next();
                //runtime.sendEvent(event);
                context.publish(event);
            }
            context.advanceTime(stop);
        }

    }

    private void replayStep(Instant start, Instant stop) {
        Iterator<RemoteEvent> ite = queryEvents(start, stop).iterator();
        while (ite.hasNext()) {
            RemoteEvent event = ite.next();
            context.publish(event);
        }
        context.advanceTime(stop); // advance to the end of the time window to trigger any timer events
    }

    private List<RemoteEvent> queryEvents(Instant start, Instant stop) {
        final String timeField = timeFieldForOrdering(orderByTimeReceived);
        final String tradeQuery = "select t from Trade t where " + timeField + " >= ?1 and " + timeField + " <= ?2";
        final String bookQuery = "select b from Book b where " + timeField + " >= ?1 and " + timeField + " <= ?2";
        final List<RemoteEvent> events = new ArrayList<>();
        events.addAll(PersistUtil.queryList(Trade.class, tradeQuery, start, stop));
        events.addAll(PersistUtil.queryList(Book.class, bookQuery, start, stop));
        Collections.sort(events, orderByTimeReceived ? timeReceivedComparator : timeHappenedComparator);
        return events;
    }

    private static Instant getEventsStart(boolean orderByRemoteTime) {
        String timeField = timeFieldForOrdering(orderByRemoteTime);
        Instant bookStart = PersistUtil.queryOne(Instant.class, "select min(" + timeField + ") from Book");
        Instant tradeStart = PersistUtil.queryOne(Instant.class, "select min(" + timeField + ") from Trade");
        if (bookStart == null && tradeStart == null)
            return null;
        if (bookStart == null)
            return tradeStart;
        if (tradeStart == null)
            return bookStart;
        return tradeStart.isBefore(bookStart) ? tradeStart : bookStart;
    }

    private static Instant getEventsEnd(boolean orderByTimeReceived) {
        final String timeField = timeFieldForOrdering(orderByTimeReceived);
        // queries use max(time)+1 because the end of a range is exclusive, and we want to include the last event
        Instant bookEnd = PersistUtil.queryOne(Instant.class, "select max(" + timeField + ") from Book");
        Instant tradeEnd = PersistUtil.queryOne(Instant.class, "select max(" + timeField + ") from Trade");
        if (bookEnd == null && tradeEnd == null)
            return null;
        if (bookEnd == null)
            return tradeEnd;
        if (tradeEnd == null)
            return bookEnd;
        return tradeEnd.isAfter(bookEnd) ? tradeEnd : bookEnd;
    }

    private static String timeFieldForOrdering(boolean orderByTimeReceived) {
        return orderByTimeReceived ? "timeReceived" : "time";
    }

    private static final Comparator<RemoteEvent> timeReceivedComparator = new Comparator<RemoteEvent>() {
        @Override
        public int compare(RemoteEvent event, RemoteEvent event2) {
            return event.getTimeReceived().compareTo(event2.getTimeReceived());
        }
    };

    private static final Comparator<RemoteEvent> timeHappenedComparator = new Comparator<RemoteEvent>() {
        @Override
        public int compare(RemoteEvent event, RemoteEvent event2) {
            return event.getTime().compareTo(event2.getTime());
        }
    };

    public class EventTimeManager implements Context.TimeProvider {
        @Override
        public Instant getInitialTime() {
            return replayTimeInterval.getStart().toInstant();
        }

        @Override
        public Instant nextTime(Event event) {
            if (orderByTimeReceived && event instanceof RemoteEvent) {
                RemoteEvent remoteEvent = (RemoteEvent) event;
                return remoteEvent.getTimeReceived();
            } else
                return event.getTime();
        }
    }

    private final Interval replayTimeInterval;
    private static ExecutorService service;
    private final Context context;
    private static final Duration timeStep = Duration.standardDays(28); // how many rows from the DB to gather in one batch
    private final boolean orderByTimeReceived;
}

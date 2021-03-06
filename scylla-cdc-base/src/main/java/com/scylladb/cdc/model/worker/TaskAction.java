package com.scylladb.cdc.model.worker;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Preconditions;
import com.google.common.flogger.FluentLogger;
import com.scylladb.cdc.cql.WorkerCQL.Reader;
import com.scylladb.cdc.model.FutureUtils;

public abstract class TaskAction {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    public abstract CompletableFuture<TaskAction> run();

    public static TaskAction createFirstAction(WorkerConfiguration workerConfiguration, Task task) {
        return new ReadNewWindowTaskAction(workerConfiguration, task, 0);
    }

    private static final class ReadNewWindowTaskAction extends TaskAction {
        private final WorkerConfiguration workerConfiguration;
        private final Task task;
        private final int tryAttempt;

        protected ReadNewWindowTaskAction(WorkerConfiguration workerConfiguration, Task task, int tryAttempt) {
            this.workerConfiguration = Preconditions.checkNotNull(workerConfiguration);
            this.task = Preconditions.checkNotNull(task);
            Preconditions.checkArgument(tryAttempt >= 0);
            this.tryAttempt = tryAttempt;
        }

        @Override
        public CompletableFuture<TaskAction> run() {
            // Wait future might end prematurely - when transport
            // requested stop. That could mean we create a reader
            // for a window intersecting with confidence window.
            // (reading too fresh data).
            //
            // Fortunately, to consume a change we queue
            // ReadNewWindowTaskAction, but as transport requested
            // stop, new TaskActions are not started and changes
            // from that "incorrect" window will not be consumed.
            CompletableFuture<Void> waitFuture = waitForWindow();

            CompletableFuture<Reader> readerFuture = waitFuture.thenCompose(w -> workerConfiguration.cql.createReader(task));
            CompletableFuture<TaskAction> taskActionFuture = readerFuture
                    .thenApply(reader -> new ReadChangeTaskAction(workerConfiguration, task, reader, tryAttempt));
            return FutureUtils.thenComposeExceptionally(taskActionFuture, ex -> {
                // Exception occured while starting up the reader. Retry by starting
                // this TaskAction once again.
                long backoffTime = workerConfiguration.workerRetryBackoff.getRetryBackoffTimeMs(tryAttempt);
                logger.atSevere().withCause(ex).log("Error while starting reading next window. Task: %s. " +
                        "Task state: %s. Will retry after backoff (%d ms).", task.id, task.state, backoffTime);
                return workerConfiguration.delayedFutureService.delayedFuture(backoffTime)
                        .thenApply(t -> new ReadNewWindowTaskAction(workerConfiguration, task, tryAttempt + 1));
            });
        }

        private CompletableFuture<Void> waitForWindow() {
            Date end = task.state.getWindowEndTimestamp().toDate();
            Date now = new Date();
            long toWait = end.getTime() - now.getTime() + workerConfiguration.confidenceWindowSizeMs;
            if (toWait > 0) {
                return workerConfiguration.delayedFutureService.delayedFuture(toWait);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class ReadChangeTaskAction extends TaskAction {
        private final WorkerConfiguration workerConfiguration;
        private final Task task;
        private final Reader reader;
        private final int tryAttempt;

        public ReadChangeTaskAction(WorkerConfiguration workerConfiguration, Task task, Reader reader, int tryAttempt) {
            this.workerConfiguration = Preconditions.checkNotNull(workerConfiguration);
            this.task = Preconditions.checkNotNull(task);
            this.reader = Preconditions.checkNotNull(reader);
            Preconditions.checkArgument(tryAttempt >= 0);
            this.tryAttempt = tryAttempt;
        }
        
        @Override
        public CompletableFuture<TaskAction> run() {
            workerConfiguration.transport.setState(task.id, task.state);

            CompletableFuture<TaskAction> taskActionFuture = reader.nextChange().
                    thenApply(change -> new ConsumeChangeTaskAction(workerConfiguration, task, reader, change, tryAttempt));
            return FutureUtils.thenComposeExceptionally(taskActionFuture, ex -> {
                // Exception occured while reading the window, we will have to restart
                // ReadNewWindowTaskAction - read a window from state defined in task.
                long backoffTime = workerConfiguration.workerRetryBackoff.getRetryBackoffTimeMs(tryAttempt);
                logger.atSevere().withCause(ex).log("Error while reading a CDC change. Task: %s. " +
                        "Task state: %s. Will retry after backoff (%d ms).", task.id, task.state, backoffTime);
                return workerConfiguration.delayedFutureService.delayedFuture(backoffTime)
                        .thenApply(t -> new ReadNewWindowTaskAction(workerConfiguration, task, tryAttempt + 1));
            });
        }
    }

    private static final class ConsumeChangeTaskAction extends TaskAction {
        private final WorkerConfiguration workerConfiguration;
        private final Task task;
        private final Reader reader;
        private final Optional<RawChange> change;
        private final int tryAttempt;

        public ConsumeChangeTaskAction(WorkerConfiguration workerConfiguration, Task task, Reader reader, Optional<RawChange> change, int tryAttempt) {
            this.workerConfiguration = Preconditions.checkNotNull(workerConfiguration);
            this.task = Preconditions.checkNotNull(task);
            this.reader = Preconditions.checkNotNull(reader);
            this.change = Preconditions.checkNotNull(change);
            Preconditions.checkArgument(tryAttempt >= 0);
            this.tryAttempt = tryAttempt;
        }

        @Override
        public CompletableFuture<TaskAction> run() {
            if (change.isPresent()) {
                Task updatedTask = task.updateState(change.get().getId());
                CompletableFuture<TaskAction> taskActionFuture = workerConfiguration.consumer.consume(task, change.get())
                        .thenApply(q -> new ReadChangeTaskAction(workerConfiguration, updatedTask, reader, tryAttempt));

                return FutureUtils.thenComposeExceptionally(taskActionFuture, ex -> {
                    // Exception occured while consuming the change, we will have to restart
                    // ReadNewWindowTaskAction - read a window from state defined in task.
                    long backoffTime = workerConfiguration.workerRetryBackoff.getRetryBackoffTimeMs(tryAttempt);
                    logger.atSevere().withCause(ex).log("Error while executing consume() method provided to the library. Task: %s. " +
                            "Task state: %s. Will retry after backoff (%d ms).", task.id, task.state, backoffTime);
                    return workerConfiguration.delayedFutureService.delayedFuture(backoffTime)
                            .thenApply(t -> new ReadNewWindowTaskAction(workerConfiguration, task, tryAttempt + 1));
                });
            } else {
                if (tryAttempt > 0) {
                    logger.atWarning().log("Successfully finished reading a window after %d tries. Task: %s. " +
                            "Task state: %s.", tryAttempt, task.id, task.state);
                }

                return CompletableFuture.completedFuture(new MoveToNextWindowTaskAction(workerConfiguration, task));
            }
        }
    }

    private static final class MoveToNextWindowTaskAction extends TaskAction {
        private final WorkerConfiguration workerConfiguration;
        private final Task task;

        public MoveToNextWindowTaskAction(WorkerConfiguration workerConfiguration, Task task) {
            this.workerConfiguration = Preconditions.checkNotNull(workerConfiguration);
            this.task = Preconditions.checkNotNull(task);
        }

        @Override
        public CompletableFuture<TaskAction> run() {
            TaskState newState = task.state.moveToNextWindow(workerConfiguration.queryTimeWindowSizeMs);
            workerConfiguration.transport.moveStateToNextWindow(task.id, newState);
            Task newTask = task.updateState(newState);
            return CompletableFuture.completedFuture(new ReadNewWindowTaskAction(workerConfiguration, newTask, 0));
        }
    }
}

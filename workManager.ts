import { NativeModules, AppRegistry, AppState, NativeAppEventEmitter, EmitterSubscription } from "react-native"

// Improved type definitions with better constraints
type NetworkConstraint = "connected" | "metered" | "notRoaming" | "unmetered" | "notRequired"
type BatteryConstraint = "charging" | "notLow" | "notRequired"
type StorageConstraint = "notLow" | "notRequired"
type IdleConstraint = "idle" | "notRequired"
type ForegroundBehaviour = "headlessTask" | "foreground" | "blocking"
type WorkerType = "periodic" | "queue"
type WorkResult = "success" | "failure" | "retry"

interface WorkerConstraints {
    network?: NetworkConstraint
    battery?: BatteryConstraint
    storage?: StorageConstraint
    idle?: IdleConstraint
}

interface WorkerNotification {
    title: string
    text: string
}

interface GenericWorker<T extends WorkerType> {
    type: T
    name: string
    timeout?: number
    foregroundBehaviour?: ForegroundBehaviour
    constraints?: WorkerConstraints
    notification: WorkerNotification
}

interface QueueWorker<P,V,T extends "queue"> extends GenericWorker<T> {
    workflow: (payload: P) => Promise<{ result: "success" | "failure" | "retry", value: V }>
    repeatInterval?: never,
}

export const isQueueWorker = (worker: any): worker is QueueWorker<any,any,"queue"> => worker.type && worker.type==="queue"

interface PeriodicWorker<T extends "periodic"> extends GenericWorker<T> {
    workflow: () => Promise<void>,
    repeatInterval?: number,
}

export const isPeriodicWorker = (worker: any): worker is PeriodicWorker<"periodic"> => worker.type && worker.type==="periodic"

export type Worker<P,V,T extends "queue"|"periodic"> = T extends "queue" ? QueueWorker<P,V,T> : T extends "periodic" ? PeriodicWorker<T> : never

const registeredWorkers = new Map<string, EmitterSubscription>();

async function setWorker<T extends WorkerType, P = any, V = any>(
    worker: Worker<P, V, T>
): Promise<T extends "periodic" ? string : void> {
    try {
        const { workflow, constraints, notification, ..._worker } = worker;
        const workerConfiguration = {
            repeatInterval: 15,
            timeout: 10,
            foregroundBehaviour: "blocking" as const,
            ..._worker,
            ...notification
        };

        const work = async (data: { id: string; payload: string }) => {
            try {
                if (isPeriodicWorker(worker)) {
                    await worker.workflow();
                    await NativeModules.BackgroundWorker.result(data.id, JSON.stringify(null), "success");
                } else if (isQueueWorker(worker)) {
                    const { result, value } = await worker.workflow(JSON.parse(data.payload));
                    await NativeModules.BackgroundWorker.result(data.id, JSON.stringify(value), result);
                } else {
                    throw new Error("Invalid worker type");
                }
            } catch (error) {
                await NativeModules.BackgroundWorker.result(
                    data.id,
                    JSON.stringify(error instanceof Error ? error.message : error),
                    "failure"
                );
            }
        };

        cleanupExistingWorker(worker.name);
        registerWorkerTask(worker.name, work);

        const subscription = createWorkerSubscription(worker.name, work, workerConfiguration);
        registeredWorkers.set(worker.name, subscription);

        return NativeModules.BackgroundWorker.registerWorker(workerConfiguration, constraints || {});
    } catch (error) {
        throw new Error(`Failed to set worker: ${error instanceof Error ? error.message : String(error)}`);
    }
}

function cleanupExistingWorker(workerName: string): void {
    const existingSubscription = registeredWorkers.get(workerName);
    if (existingSubscription) {
        existingSubscription.remove();
        registeredWorkers.delete(workerName);
    }
}

function registerWorkerTask(workerName: string, work: (data: { id: string; payload: string }) => Promise<void>): void {
    AppRegistry.registerHeadlessTask(workerName, () => work);
}

function createWorkerSubscription(
    workerName: string,
    work: (data: { id: string; payload: string }) => Promise<void>,
    config: { foregroundBehaviour: ForegroundBehaviour }
): EmitterSubscription {
    return NativeAppEventEmitter.addListener(workerName, async (data) => {
        if (AppState.currentState === "active") {
            if (config.foregroundBehaviour === "blocking") {
                await NativeModules.BackgroundWorker.result(data.id, JSON.stringify(null), "retry");
                return;
            }
            if (config.foregroundBehaviour === "foreground") {
                await work(data);
                return;
            }
        }
        await NativeModules.BackgroundWorker.startHeadlessTask({ ...config, ...data });
    });
}

async function enqueue<P = any>(work: { worker: string; payload?: P }): Promise<string> {
    try {
        return await NativeModules.BackgroundWorker.enqueue(work.worker, JSON.stringify(work.payload));
    } catch (error) {
        throw new Error(`Failed to enqueue work: ${error instanceof Error ? error.message : String(error)}`);
    }
}

async function cancel(id: string): Promise<void> {
    try {
        await NativeModules.BackgroundWorker.cancel(id);
    } catch (error) {
        throw new Error(`Failed to cancel work: ${error instanceof Error ? error.message : String(error)}`);
    }
}

export type WorkInfo<V> = {
    state: "failed" | "blocked" | "running" | "enqueued" | "cancelled" | "succeeded" | "unknown",
    attemptCount: number,
    value: V,
}

/**
 * Returns the WorkInfo object for the requested work
 * @param id requisited work's id
 */
function info<V>(id: string): Promise<WorkInfo<V>> {
    return new Promise((resolve,reject) => {
        NativeModules.BackgroundWorker.info(id)
            .then((_info: WorkInfo<string>) => resolve({ ..._info, value: JSON.parse(_info.value) }))
            .catch(reject)
    })
}

/**
 * Registers a listener to watch for changes on work's state
 * @param id requisited work's id
 * @param callback function to be called when work's state change
 */
function addListener<V>(id: string,callback: (info: WorkInfo<V>) => void): () => void {
    NativeModules.BackgroundWorker.addListener(id)
    const subscription = NativeAppEventEmitter.addListener(id+"info", (_info: WorkInfo<string>) =>
        callback({ ..._info, value: JSON.parse(_info.value) }))
    return () => {
        subscription.remove()
        NativeModules.BackgroundWorker.removeListener(id)
    }
}

export default {
    setWorker,
    enqueue,
    cancel,
    info,
    addListener,
} as const;
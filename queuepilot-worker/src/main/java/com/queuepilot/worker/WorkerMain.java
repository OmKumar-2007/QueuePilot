package com.queuepilot.worker;


/** Entry point of a worker process. */
public final class WorkerMain {

    private WorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(WorkerConfig.USAGE);
            return;
        }
        WorkerConfig config;
        try {
            config = WorkerConfig.fromArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(WorkerConfig.USAGE);
            System.exit(2);
            return;
        }

        TaskExecutorRegistry registry = TaskExecutorRegistry.load(config.pluginsDir());
        WorkerRuntime runtime = new WorkerRuntime(config, new HttpCoordinatorClient(config.coordinatorUrl()), registry);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop, "qp-shutdown"));
        runtime.start();
    }
}

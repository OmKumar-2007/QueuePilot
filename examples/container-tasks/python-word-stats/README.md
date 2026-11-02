# Container task example: python-word-stats (Phase 9)

A non-Java task packaged as a container image. **Status: implemented but not verified on the development machine, because Docker is not installed there.** `ContainerIsolationTest` runs automatically wherever Docker is available.

## Build and pin

```bash
docker build -t queuepilot/python-word-stats:1.0.0 examples/container-tasks/python-word-stats
docker inspect --format "{{index .RepoDigests 0}}" queuepilot/python-word-stats:1.0.0
```

A locally built image has no repository digest until it is pushed to a registry; push it (e.g. to a local registry) and use the `name@sha256:...` reference. QueuePilot refuses unpinned images so a tag cannot silently change what runs.

## Register

Put the digest into `manifest.json`, then (as admin) register and enable it from the Task registry page or:

```bash
curl -u admin:$QP_ADMIN_PASSWORD -H "Content-Type: application/json" --data @examples/container-tasks/python-word-stats/manifest.json http://localhost:8080/api/admin/tasks
```

## Run a worker that accepts container tasks

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0.jar --name container-worker --containers
```

The worker advertises the `docker` capability only if `docker version` succeeds.

## Sandbox applied by the worker (`DockerCommandBuilder`)

`--network none`, `--read-only`, `--tmpfs /tmp:noexec,nosuid,size=64m`, `--cpus`/`--memory`/`--memory-swap` from the manifest, `--pids-limit 64`, `--user 65534:65534`, `--cap-drop ALL`, `--security-opt no-new-privileges`, only the attempt's scratch folder mounted at `/work`, container killed on timeout or revocation. The Docker socket is never mounted into a container, and containers are started by workers, never by the coordinator.

Residual risks: the Docker daemon itself runs with high privileges on the host; kernel-level container escapes are outside what these flags can prevent. Do not run untrusted images on a machine holding sensitive data.

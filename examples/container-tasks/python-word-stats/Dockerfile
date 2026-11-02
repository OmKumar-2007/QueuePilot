FROM python:3.12-alpine
# The worker additionally forces --user 65534, --read-only, --network none and resource limits.
COPY stats.py /app/stats.py
USER 65534:65534
ENTRYPOINT []

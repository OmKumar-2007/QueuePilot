package com.queuepilot.tasks.builtin;

import com.queuepilot.task.ProgressTracker;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes a cryptographic digest of an uploaded file, reading it in 64 KB blocks. */
public class FileChecksumTask implements TaskExecutor {

    @Override
    public String taskType() {
        return "file-checksum";
    }


    @Override
    public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
        String algorithm = input.getString("algorithm", "SHA-256");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw TaskFailure.permanent("UNSUPPORTED_ALGORITHM", "Unknown digest " + algorithm);
        }

        try {
            Path file = context.inputFile(input.getString("file", null));
            long size = Files.size(file);
            ProgressTracker tracker = new ProgressTracker(context, Math.max(1, size));
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                    read += n;
                    tracker.advance(read, String.format("Hashed %,d of %,d bytes", read, size));
                }
            }
            return TaskOutput.create()
                    .put("algorithm", algorithm)
                    .put("sizeBytes", size)
                    .put("digest", HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new TaskFailure("IO_ERROR", "Could not read file: " + e.getMessage(), true, e);
        }
    }
}

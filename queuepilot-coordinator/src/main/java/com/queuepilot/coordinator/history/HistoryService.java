package com.queuepilot.coordinator.history;

import com.queuepilot.coordinator.artifact.ArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Permanently deletes all logs and the finished jobs that nothing still needs. A finished job is
 * kept while an unfinished job depends on it, or while its workflow is unfinished: a BLOCKED job
 * only starts once it can see all its prerequisites SUCCEEDED, so removing one would strand it.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private static final String ACTIVE = "('BLOCKED', 'QUEUED', 'RUNNING')";
    private static final String REMOVABLE_JOBS = """
            select j.id from job j
            where j.state not in %1$s
              and not exists (select 1 from job_dependency d join job dependant on dependant.id = d.job_id
                              where d.prerequisite_id = j.id and dependant.state in %1$s)
              and not exists (select 1 from job sibling
                              where sibling.workflow_id = j.workflow_id and sibling.state in %1$s)
            """.formatted(ACTIVE);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ArtifactService artifacts;

    public HistoryService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactService artifacts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.artifacts = artifacts;
    }

    public record Cleared(int jobs, int logs, int files, int jobsKept) {
    }

    private record Removed(Cleared cleared, List<String> fileIds) {
    }

    public Cleared clear() {
        Removed removed = transactions.execute(status -> removeRows());
        // Only after the commit: a rolled-back clear must not have lost any files.
        artifacts.deleteFiles(removed.fileIds());
        log.info("History cleared: {}", removed.cleared());
        return removed.cleared();
    }

    private Removed removeRows() {
        List<String> jobIds = jdbc.queryForList(REMOVABLE_JOBS, String.class);
        List<String> fileIds = new ArrayList<>();
        for (String jobId : jobIds) {
            fileIds.addAll(jdbc.queryForList("select id from artifact where job_id = ?", String.class, jobId));
        }
        List<Object[]> args = jobIds.stream().map(id -> new Object[] {id}).toList();
        jdbc.batchUpdate("delete from attempt where job_id = ?", args);
        jdbc.batchUpdate("delete from artifact where job_id = ?", args);
        jdbc.batchUpdate("delete from job_dependency where job_id = ?", args);
        jdbc.batchUpdate("delete from job_dependency where prerequisite_id = ?", args);
        jdbc.batchUpdate("delete from job where id = ?", args);
        jdbc.update("delete from workflow where id not in (select workflow_id from job where workflow_id is not null)");
        int logs = jdbc.update("delete from system_event");
        Integer kept = jdbc.queryForObject("select count(*) from job where state not in " + ACTIVE, Integer.class);
        return new Removed(new Cleared(jobIds.size(), logs, fileIds.size(), kept), fileIds);
    }
}

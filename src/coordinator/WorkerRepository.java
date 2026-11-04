package coordinator;

import core.WorkerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface WorkerRepository extends JpaRepository<WorkerEntity, String> {

    List<WorkerEntity> findByStatusIn(Collection<WorkerStatus> statuses);

    @Query("select w.id from WorkerEntity w where w.status in (core.WorkerStatus.ONLINE, "
            + "core.WorkerStatus.DRAINING, core.WorkerStatus.PAUSED) and w.lastHeartbeatAt < :cutoff")
    List<String> findSilentWorkerIds(@Param("cutoff") Instant cutoff);

    List<WorkerEntity> findAllByOrderByNameAsc();
}

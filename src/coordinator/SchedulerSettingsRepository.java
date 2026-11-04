package coordinator;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulerSettingsRepository extends JpaRepository<SchedulerSettingsEntity, Integer> {
}

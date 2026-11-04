package coordinator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenchmarkReportRepository extends JpaRepository<BenchmarkReportEntity, String> {

    List<BenchmarkReportEntity> findAllByOrderByCreatedAtDesc();
}

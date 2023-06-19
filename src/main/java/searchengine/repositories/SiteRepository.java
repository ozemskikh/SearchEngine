package searchengine.repositories;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Site;
import searchengine.model.Status;

import java.util.ArrayList;

@Repository
public interface SiteRepository extends CrudRepository<Site, Integer> {
    boolean existsByUrl(String url);

    Site findByUrl(String url);

    @Query(value = "SELECT url FROM Site WHERE status = :status")
    ArrayList<String> findAllUrlByStatus(Status status);
}
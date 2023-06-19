package searchengine.repositories;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;

import javax.transaction.Transactional;
import java.util.List;

@Repository
public interface IndexRepository extends CrudRepository<Index, Integer> {
    List<Index> findAllByLemmaId(int lemmaId);

    Index findByLemmaIdAndPageId(int lemmaId, int pageId);

    @Modifying
    @Transactional
    void deleteAllByPageId(int pageId);

    List<Index> findAllByPageIdIn(List<Integer> pageIdListToDelete);
}
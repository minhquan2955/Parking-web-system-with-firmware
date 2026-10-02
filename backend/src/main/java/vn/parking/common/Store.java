package vn.parking.common;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import vn.parking.model.BaseEntity;

/** Shared JPA repository primitives; services own all business transactions. */
@Repository
public class Store {
  @PersistenceContext private EntityManager em;

  public <T> T get(Class<T> type, UUID id) {
    var x = em.find(type, id);
    if (x == null) throw Problem.missing();
    return x;
  }

  public <T> T lock(Class<T> type, UUID id) {
    var x = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    if (x == null) throw Problem.missing();
    em.refresh(x, LockModeType.PESSIMISTIC_WRITE);
    return x;
  }

  public <T> List<T> list(Class<T> type, String where, Object... args) {
    var q = em.createQuery("from " + type.getSimpleName() + " e " + where, type);
    bind(q, args);
    return q.getResultList();
  }

  public <T> Optional<T> one(Class<T> type, String where, Object... args) {
    return list(type, where, args).stream().findFirst();
  }

  public long count(Class<?> type, String where, Object... args) {
    var q =
        em.createQuery("select count(e) from " + type.getSimpleName() + " e " + where, Long.class);
    bind(q, args);
    return q.getSingleResult();
  }

  public <T> List<T> page(Class<T> type, String where, int page, int size, Object... args) {
    var q = em.createQuery("from " + type.getSimpleName() + " e " + where, type);
    bind(q, args);
    return q.setFirstResult(page * size).setMaxResults(size).getResultList();
  }

  public <T extends BaseEntity> T add(T entity) {
    em.persist(entity);
    return entity;
  }

  public void flush() {
    em.flush();
  }

  public long nextOrderCode() {
    return ((Number)
            em.createNativeQuery("select nextval('provider_order_code_seq')").getSingleResult())
        .longValue();
  }

  public void advisory(String key) {
    em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .setParameter("key", key)
        .getSingleResult();
  }

  private void bind(Query q, Object... args) {
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
  }
}

package com.kilivana.products.repository;

import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.domain.SellerType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    @EntityGraph(attributePaths = {"owner", "category"})
    Optional<Product> findWithOwnerById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from Product product join fetch product.owner left join fetch product.category where product.id = :id")
    Optional<Product> findWithOwnerByIdForUpdate(UUID id);

    @EntityGraph(attributePaths = "owner")
    List<Product> findByStatus(ProductStatus status);

    @EntityGraph(attributePaths = "owner")
    List<Product> findByStatusAndSector(ProductStatus status, Sector sector);

    @EntityGraph(attributePaths = {"owner", "category"})
    List<Product> findByOwnerId(UUID ownerId);

    @EntityGraph(attributePaths = {"owner", "category"})
    List<Product> findByStatusAndOwnerId(ProductStatus status, UUID ownerId);

    @Query("""
            select product from Product product
            join fetch product.owner
            left join fetch product.category
            where product.status = :status
              and (:sector is null or product.sector = :sector)
              and (:categoryId is null or :categoryId = product.category.id)
              and (:ownerId is null or :ownerId = product.owner.id)
              and (:query is null or lower(product.title) like lower(concat('%', cast(:query as string), '%')))
            """)
    Page<Product> browse(ProductStatus status, Sector sector, UUID categoryId, UUID ownerId, String query, Pageable pageable);

    @Query("""
            select product from Product product
            join fetch product.owner
            left join fetch product.category
            where (:status is null or product.status = :status)
              and (:sector is null or product.sector = :sector)
              and (:categoryId is null or :categoryId = product.category.id)
              and (:ownerId is null or :ownerId = product.owner.id)
              and (:query is null or lower(product.title) like lower(concat('%', cast(:query as string), '%')))
            """)
    Page<Product> adminBrowse(ProductStatus status, Sector sector, UUID categoryId, UUID ownerId, String query, Pageable pageable);

    long countByStatus(ProductStatus status);

    long countBySellerType(SellerType sellerType);

    long countBySellerTypeAndStatus(SellerType sellerType, ProductStatus status);
}

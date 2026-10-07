package com.kilivana.products.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_images")
public class ProductImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true, length = 255)
    private String storageKey;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false)
    private int sortOrder;

    protected ProductImage() {
    }

    public ProductImage(
            Product product,
            String storageKey,
            String fileName,
            String contentType,
            long sizeBytes,
            String url,
            int sortOrder) {
        this.product = product;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.url = url;
        this.sortOrder = sortOrder;
    }

    public Product getProduct() {
        return product;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getUrl() {
        return url;
    }

    public void updateUrl(String url) {
        this.url = url;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

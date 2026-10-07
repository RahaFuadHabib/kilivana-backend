package com.kilivana.products.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.media.MediaStorage;
import com.kilivana.media.StoredFile;
import com.kilivana.products.api.ProductImageResponse;
import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductImage;
import com.kilivana.products.repository.ProductImageRepository;
import com.kilivana.products.repository.ProductRepository;
import com.kilivana.security.CurrentUser;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductImageService {
    public static final int MAX_IMAGES = 10;
    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final MediaStorage storage;
    private final CurrentUser currentUser;
    private final ProductService productService;

    public ProductImageService(ProductRepository products, ProductImageRepository images,
            MediaStorage storage, CurrentUser currentUser, ProductService productService) {
        this.products = products;
        this.images = images;
        this.storage = storage;
        this.currentUser = currentUser;
        this.productService = productService;
    }

    @Transactional
    public ProductImageResponse upload(UUID productId, MultipartFile file) {
        var actor = currentUser.required();
        Product product = products.findWithOwnerByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!product.getOwner().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only manage your own products");
        }
        var existing = images.findByProductIdOrderBySortOrderAsc(productId);
        if (existing.size() >= MAX_IMAGES) {
            throw new BusinessConflictException("A product can have at most 10 images");
        }
        int sortOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        StoredFile stored = storage.store(file);
        boolean synchronizedCleanup = TransactionSynchronizationManager.isSynchronizationActive();
        try {
            if (synchronizedCleanup) {
                // Covers failures during flush and transaction commit, including an outer rollback.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            cleanup(stored.storageKey());
                        }
                    }
                });
            }
            ProductImage image = new ProductImage(product, stored.storageKey(), stored.fileName(),
                    stored.contentType(), stored.sizeBytes(), "", sortOrder);
            images.save(image);
            image.updateUrl("/api/v1/products/" + productId + "/images/" + image.getId() + "/content");
            images.flush();
            return new ProductImageResponse(image.getId(), image.getFileName(), image.getContentType(),
                    image.getSizeBytes(), image.getUrl(), image.getSortOrder());
        } catch (RuntimeException exception) {
            if (!synchronizedCleanup) {
                cleanup(stored.storageKey());
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ImageContent content(UUID productId, UUID imageId) throws IOException {
        // Use the same visibility policy as listing details (public ACTIVE, restricted otherwise).
        productService.getById(productId);
        ProductImage image = images.findById(imageId)
                .filter(candidate -> candidate.getProduct().getId().equals(productId))
                .orElseThrow(() -> new ResourceNotFoundException("Product image not found"));
        String expectedUrl = "/api/v1/products/" + productId + "/images/" + imageId + "/content";
        if (!expectedUrl.equals(image.getUrl())) {
            throw new ResourceNotFoundException("Product image has no uploaded content");
        }
        try (var input = storage.open(image.getStorageKey())) {
            return new ImageContent(image.getContentType(), input.readAllBytes());
        }
    }

    private void cleanup(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Failed to remove rolled-back product image {}", key, exception);
        }
    }

    public record ImageContent(String contentType, byte[] bytes) { }
}

package com.kilivana.products.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.products.api.CreateProductRequest;
import com.kilivana.products.api.ProductImageResponse;
import com.kilivana.products.api.ProductResponse;
import com.kilivana.products.api.UpdateProductRequest;
import com.kilivana.products.domain.Category;
import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductImage;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.domain.SellerType;
import com.kilivana.products.repository.CategoryRepository;
import com.kilivana.products.repository.ProductImageRepository;
import com.kilivana.products.repository.ProductRepository;
import com.kilivana.products.repository.SavedProductRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private static final BigDecimal DEFAULT_MINIMUM_ORDER = BigDecimal.ONE;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final SavedProductRepository savedProductRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductImageRepository productImageRepository,
            SavedProductRepository savedProductRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productImageRepository = productImageRepository;
        this.savedProductRepository = savedProductRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    public Page<ProductResponse> browse(Sector sector, UUID categoryId, UUID sellerId, String query, Pageable pageable) {
        return productRepository.browse(ProductStatus.ACTIVE, sector, categoryId, sellerId, query, pageable)
                .map(this::toResponse);
    }

    public Page<ProductResponse> adminBrowse(
            ProductStatus status, Sector sector, UUID categoryId, UUID sellerId, String query, Pageable pageable) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can browse the full catalog");
        }
        if (query == null || query.isBlank()) {
            query = null;
        }
        return productRepository.adminBrowse(status, sector, categoryId, sellerId, query, pageable)
                .map(this::toResponse);
    }

    public ProductResponse toProductResponse(Product product) {
        return toResponse(product);
    }

    public long countByStatus(ProductStatus status) {
        return productRepository.countByStatus(status);
    }

    public List<ProductResponse> sellerProducts(UUID sellerId) {
        return productRepository.findByStatusAndOwnerId(ProductStatus.ACTIVE, sellerId).stream().map(this::toResponse).toList();
    }

    public List<ProductResponse> myProducts() {
        AuthenticatedUser actor = currentUser.required();
        return productRepository.findByOwnerId(actor.getId()).stream().map(this::toResponse).toList();
    }

    public ProductResponse getById(UUID productId) {
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (product.getStatus() != ProductStatus.ACTIVE) {
            AuthenticatedUser actor = currentUser.maybe();
            if (actor == null) {
                throw new ResourceNotFoundException("Product not found");
            }
            boolean canView = product.getOwner().getId().equals(actor.getId())
                    || actor.getRole() == UserRole.ADMIN || actor.isVerifiedInspector();
            if (!canView) {
                throw new ResourceNotFoundException("Product not found");
            }
        }
        return toResponse(product);
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        AuthenticatedUser actor = currentUser.required();
        UserRole role = actor.getRole();
        if (role != UserRole.FARMER && role != UserRole.SUPPLIER) {
            throw new UnauthorizedOperationException("Only farmers and suppliers can create products");
        }

        User owner = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Sector sector = role == UserRole.FARMER ? Sector.FARM_PRODUCE : Sector.AGRI_INPUTS;
        Category category = resolveCategory(request.categoryId(), sector);
        Product product = new Product(
                owner,
                role == UserRole.FARMER ? SellerType.FARMER : SellerType.SUPPLIER,
                sector,
                ProductStatus.ACTIVE,
                category,
                request.title().trim(),
                request.description().trim(),
                request.unit().trim(),
                request.unitPrice(),
                request.availableQuantity(),
                request.lowStockThreshold(),
                request.minimumOrderQuantity() == null ? DEFAULT_MINIMUM_ORDER : request.minimumOrderQuantity());
        productRepository.save(product);
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(UUID productId, UpdateProductRequest request) {
        AuthenticatedUser actor = currentUser.required();
        Product product = requireOwnedProduct(productId, actor);
        Category category = request.categoryId() == null ? product.getCategory()
                : resolveCategory(request.categoryId(), product.getSector());
        product.update(
                request.title() == null ? product.getTitle() : request.title().trim(),
                request.description() == null ? product.getDescription() : request.description().trim(),
                request.unit() == null ? product.getUnit() : request.unit().trim(),
                request.unitPrice() == null ? product.getUnitPrice() : request.unitPrice(),
                request.availableQuantity() == null ? product.getAvailableQuantity() : request.availableQuantity(),
                category,
                request.lowStockThreshold() == null ? product.getLowStockThreshold() : request.lowStockThreshold(),
                request.minimumOrderQuantity() == null ? product.getMinimumOrderQuantity() : request.minimumOrderQuantity());
        return toResponse(product);
    }

    @Transactional
    public ProductResponse unlist(UUID productId) {
        AuthenticatedUser actor = currentUser.required();
        Product product = requireOwnedProduct(productId, actor);
        product.hide();
        return toResponse(product);
    }

    @Transactional
    public ProductResponse moderate(UUID productId, ProductStatus status) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can moderate products");
        }
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        switch (status) {
            case ACTIVE -> product.activate();
            case HIDDEN -> product.hide();
            default -> throw new BusinessConflictException("Moderation only supports ACTIVE or HIDDEN outcomes");
        }
        return toResponse(product);
    }

    @Transactional
    public ProductImageResponse registerImage(UUID productId, String fileName, String contentType, long sizeBytes, String url) {
        AuthenticatedUser actor = currentUser.required();
        Product product = requireOwnedProduct(productId, actor);
        String storageKey = "product-" + productId + "-" + UUID.randomUUID();
        int sortOrder = productImageRepository.findByProductIdOrderBySortOrderAsc(productId).size();
        ProductImage image = new ProductImage(product, storageKey, fileName, contentType, sizeBytes, url, sortOrder);
        productImageRepository.save(image);
        return toResponse(image);
    }

    public List<ProductImageResponse> imagesFor(UUID productId) {
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (product.getStatus() != ProductStatus.ACTIVE) {
            AuthenticatedUser actor = currentUser.maybe();
            boolean canView = actor != null
                    && (product.getOwner().getId().equals(actor.getId())
                    || actor.getRole() == UserRole.ADMIN
                    || actor.isVerifiedInspector());
            if (!canView) {
                throw new ResourceNotFoundException("Product not found");
            }
        }
        return productImageRepository.findByProductIdOrderBySortOrderAsc(productId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void save(UUID productId) {
        AuthenticatedUser actor = currentUser.required();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (savedProductRepository.existsByUserIdAndProductId(actor.getId(), productId)) {
            return;
        }
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        savedProductRepository.save(new com.kilivana.products.domain.SavedProduct(user, product));
    }

    @Transactional
    public void unsave(UUID productId) {
        AuthenticatedUser actor = currentUser.required();
        savedProductRepository.deleteByUserIdAndProductId(actor.getId(), productId);
    }

    public List<ProductResponse> saved() {
        AuthenticatedUser actor = currentUser.required();
        return savedProductRepository.findByUserIdOrderByCreatedAtDesc(actor.getId())
                .stream()
                .map(saved -> saved.getProduct())
                .filter(product -> product.getStatus() == ProductStatus.ACTIVE)
                .map(this::toResponse)
                .toList();
    }

    private Product requireOwnedProduct(UUID productId, AuthenticatedUser actor) {
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!product.getOwner().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only manage your own products");
        }
        return product;
    }

    private Category resolveCategory(UUID categoryId, Sector sector) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (!category.isActive()) {
            throw new BusinessConflictException("Category is inactive");
        }
        if (category.getSector() != sector) {
            throw new BusinessConflictException("Category sector must match product sector");
        }
        return category;
    }

    private ProductResponse toResponse(Product product) {
        Category category = product.getCategory();
        return new ProductResponse(
                product.getId(),
                product.getOwner().getId(),
                product.getOwner().getFullName(),
                product.getSellerType(),
                product.getSector(),
                product.getStatus(),
                category == null ? null : category.getId(),
                category == null ? null : category.getName(),
                product.getTitle(),
                product.getDescription(),
                product.getUnit(),
                product.getUnitPrice(),
                product.getAvailableQuantity(),
                product.getReservedQuantity(),
                product.getSoldQuantity(),
                product.getLowStockThreshold(),
                product.getMinimumOrderQuantity(),
                product.isLowStock(),
                imagesFor(product.getId()),
                product.getCreatedAt());
    }

    private ProductImageResponse toResponse(ProductImage image) {
        return new ProductImageResponse(
                image.getId(),
                image.getFileName(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getUrl(),
                image.getSortOrder());
    }
}

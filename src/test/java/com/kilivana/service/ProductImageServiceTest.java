package com.kilivana.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.media.*;
import com.kilivana.products.domain.*;
import com.kilivana.products.repository.*;
import com.kilivana.products.service.*;
import com.kilivana.security.*;
import com.kilivana.users.domain.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {
    @Mock ProductRepository products;
    @Mock ProductImageRepository images;
    @Mock MediaStorage storage;
    @Mock CurrentUser user;
    @Mock ProductService listings;
    ProductImageService service;
    Product product;
    UUID productId = UUID.randomUUID(), ownerId = UUID.randomUUID(), imageId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", "client.png", "image/png", new byte[]{1});
    StoredFile stored = new StoredFile("2026-10-05/actual.png", "actual.png", "image/png", 8);

    @BeforeEach void setup() {
        service = new ProductImageService(products, images, storage, user, listings);
        product = mock(Product.class);
    }

    void owned() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(product.getOwner()).thenReturn(owner);
        when(products.findWithOwnerByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(user.required()).thenReturn(new AuthenticatedUser(ownerId, "owner@test.com", "", UserRole.FARMER));
    }

    void validStore() {
        when(storage.store(file)).thenReturn(stored);
        when(images.save(any())).thenAnswer(invocation -> {
            ProductImage image = invocation.getArgument(0);
            ReflectionTestUtils.setField(image, "id", imageId);
            return image;
        });
    }

    @Test void ownerUploadsStoredMetadata() {
        owned(); validStore();
        var response = service.upload(productId, file);
        assertThat(response.fileName()).isEqualTo("actual.png");
        assertThat(response.sizeBytes()).isEqualTo(8);
        assertThat(response.url()).isEqualTo("/api/v1/products/" + productId + "/images/" + imageId + "/content");
        verify(images).save(argThat(image -> image.getStorageKey().equals(stored.storageKey())
                && image.getContentType().equals(stored.contentType())));
        verify(images).flush();
        verify(storage, never()).delete(any());
    }

    @Test void nonOwnerRejectedBeforeStorage() {
        owned();
        when(user.required()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), "other@test.com", "", UserRole.FARMER));
        assertThatThrownBy(() -> service.upload(productId, file)).isInstanceOf(UnauthorizedOperationException.class);
        verifyNoInteractions(storage, images);
    }

    @Test void tenthImageIsAllowedButEleventhRejected() {
        owned(); validStore();
        when(images.findByProductIdOrderBySortOrderAsc(productId)).thenReturn(Collections.nCopies(9,
                new ProductImage(product, "old", "old.png", "image/png", 8, "external", 8)));
        assertThat(service.upload(productId, file).sortOrder()).isEqualTo(9);
        when(images.findByProductIdOrderBySortOrderAsc(productId)).thenReturn(Collections.nCopies(10, mock(ProductImage.class)));
        assertThatThrownBy(() -> service.upload(productId, file)).isInstanceOf(BusinessConflictException.class);
        verify(storage, times(1)).store(file);
    }

    @Test void invalidImageCreatesNoMetadata() {
        owned();
        when(storage.store(file)).thenThrow(new MediaStorageException("Invalid image"));
        assertThatThrownBy(() -> service.upload(productId, file)).isInstanceOf(MediaStorageException.class);
        verify(images, never()).save(any());
    }

    @Test void persistenceFailureDeletesStoredFileAndPreservesFailure() {
        owned(); validStore();
        doThrow(new IllegalStateException("DB failure")).when(images).flush();
        doThrow(new MediaStorageException("delete failure")).when(storage).delete(stored.storageKey());
        assertThatThrownBy(() -> service.upload(productId, file)).hasMessage("DB failure");
        verify(storage).delete(stored.storageKey());
    }

    @Test void rollbackAfterSuccessfulMethodDeletesFile() {
        owned(); validStore();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(productId, file);
            verify(storage, never()).delete(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(s ->
                    s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(storage).delete(stored.storageKey());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void contentUsesStoredKeyAndClosesStream() throws Exception {
        when(product.getId()).thenReturn(productId);
        var image = new ProductImage(product, stored.storageKey(), stored.fileName(), stored.contentType(), 8,
                "/api/v1/products/" + productId + "/images/" + imageId + "/content", 0);
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        var input = spy(new ByteArrayInputStream(new byte[]{1, 2}));
        when(storage.open(stored.storageKey())).thenReturn(input);
        assertThat(service.content(productId, imageId).bytes()).containsExactly(1, 2);
        verify(listings).getById(productId);
        verify(input).close();
    }

    @Test void invisibleProductNeverOpensStorage() {
        when(listings.getById(productId)).thenThrow(new ResourceNotFoundException("Product not found"));
        assertThatThrownBy(() -> service.content(productId, imageId)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(storage, images);
    }
}

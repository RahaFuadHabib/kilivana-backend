package com.kilivana.users.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.InvalidAccountDataException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.api.PublicSellerProfileResponse;
import com.kilivana.users.api.UpdateUserProfileRequest;
import com.kilivana.users.api.UpdateUserStatusRequest;
import com.kilivana.users.api.UpdateUserVerificationRequest;
import com.kilivana.users.api.UserProfileResponse;
import com.kilivana.users.domain.DriverProfile;
import com.kilivana.users.domain.FarmerProfile;
import com.kilivana.users.domain.InspectorProfile;
import com.kilivana.users.domain.SupplierProfile;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.DriverProfileRepository;
import com.kilivana.users.repository.FarmerProfileRepository;
import com.kilivana.users.repository.InspectorProfileRepository;
import com.kilivana.users.repository.SupplierProfileRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final SupplierProfileRepository supplierProfileRepository;
    private final FarmerProfileRepository farmerProfileRepository;
    private final InspectorProfileRepository inspectorProfileRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final CurrentUser currentUser;

    public UserService(
            UserRepository userRepository,
            SupplierProfileRepository supplierProfileRepository,
            FarmerProfileRepository farmerProfileRepository,
            InspectorProfileRepository inspectorProfileRepository,
            DriverProfileRepository driverProfileRepository,
            CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.supplierProfileRepository = supplierProfileRepository;
        this.farmerProfileRepository = farmerProfileRepository;
        this.inspectorProfileRepository = inspectorProfileRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.currentUser = currentUser;
    }

    public UserProfileResponse currentProfile() {
        AuthenticatedUser actor = currentUser.required();
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        requireActive(user);
        return toProfile(user);
    }

    public UserProfileResponse profileById(UUID userId) {
        AuthenticatedUser actor = currentUser.required();
        if (!userId.equals(actor.getId()) && actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("You can only read your own private profile");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return toProfile(user);
    }

    public PublicSellerProfileResponse publicSellerProfile(UUID sellerId) {
        User user = userRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller not found"));
        if (user.getStatus() != UserStatus.ACTIVE
                || (user.getRole() != UserRole.SUPPLIER && user.getRole() != UserRole.FARMER)) {
            throw new ResourceNotFoundException("Seller not found");
        }

        String businessName = user.getRole() == UserRole.SUPPLIER
                ? supplierProfileRepository.findByUserId(user.getId())
                        .map(SupplierProfile::getBusinessName)
                        .orElse(null)
                : null;
        String farmName = null;
        String farmLocation = null;
        if (user.getRole() == UserRole.FARMER) {
            Optional<FarmerProfile> profile = farmerProfileRepository.findByUserId(user.getId());
            farmName = profile.map(FarmerProfile::getFarmName).orElse(null);
            farmLocation = user.getRegion() == null ? profile.map(FarmerProfile::getFarmLocation).orElse(null) : user.getRegion();
        }
        return new PublicSellerProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getRole(),
                user.getVerificationStatus(),
                businessName,
                farmName,
                farmLocation);
    }

    public List<UserProfileResponse> listProfiles() {
        AuthenticatedUser actor = currentUser.required();
        requireAdmin(actor);
        return userRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toProfile).toList();
    }

    public Page<UserProfileResponse> search(UserRole role, UserStatus status, Pageable pageable) {
        requireAdmin(currentUser.required());
        if (role != null && status != null) {
            return userRepository.findByRoleAndStatus(role, status, pageable).map(this::toProfile);
        }
        if (role != null) {
            return userRepository.findByRole(role, pageable).map(this::toProfile);
        }
        if (status != null) {
            return userRepository.findByStatus(status, pageable).map(this::toProfile);
        }
        return userRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toProfile);
    }

    @Transactional
    public UserProfileResponse updateStatus(UUID userId, UpdateUserStatusRequest request) {
        AuthenticatedUser actor = currentUser.required();
        requireAdmin(actor);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getId().equals(actor.getId()) && request.status() == UserStatus.SUSPENDED) {
            throw new BusinessConflictException("Administrators cannot suspend their own account");
        }
        if (user.getRole() == UserRole.ADMIN && request.status() == UserStatus.SUSPENDED) {
            throw new BusinessConflictException("Admin accounts cannot be suspended");
        }
        if (request.status() == UserStatus.SUSPENDED) {
            user.suspend();
        } else {
            user.activate();
        }
        return toProfile(user);
    }

    @Transactional
    public UserProfileResponse updateVerification(UUID userId, UpdateUserVerificationRequest request) {
        AuthenticatedUser actor = currentUser.required();
        requireAdmin(actor);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.markVerification(request.verificationStatus());
        return toProfile(user);
    }

    @Transactional
    public UserProfileResponse updateOwnProfile(UpdateUserProfileRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        requireActive(user);
        String region = request.region() == null ? user.getRegion() : AccountIdentity.region(request.region());
        if (request.farmLocation() != null) {
            if (user.getRole() != UserRole.FARMER) {
                throw new InvalidAccountDataException("farmLocation is only supported for Farmers");
            }
            String legacyRegion = AccountIdentity.region(request.farmLocation());
            if (request.region() != null && !legacyRegion.equals(region)) {
                throw new InvalidAccountDataException("farmLocation must match region");
            }
            region = legacyRegion;
        }
        if (request.username() != null) {
            String username = AccountIdentity.username(request.username());
            if (userRepository.existsByUsernameAndIdNot(username, user.getId())) {
                throw new BusinessConflictException("Username is already registered");
            }
            user.setUsername(username);
        }
        if (request.phoneNumber() != null) {
            String phone = AccountIdentity.phone(request.phoneNumber());
            if (userRepository.existsByPhoneNumberAndIdNot(phone, user.getId())) {
                throw new BusinessConflictException("Phone number is already registered");
            }
            user.setPhoneNumber(phone);
        }
        user.setRegion(region);
        if (request.fullName() != null) {
            user.setFullName(AccountIdentity.required(request.fullName(), "fullName", 100));
        }
        switch (user.getRole()) {
            case SUPPLIER -> supplierProfileRepository.findByUserId(user.getId())
                    .ifPresent(profile -> {
                        if (request.businessName() != null) {
                            profile.updateBusinessName(AccountIdentity.required(request.businessName(), "businessName", 150));
                        }
                    });
            case FARMER -> farmerProfileRepository.findByUserId(user.getId())
                    .ifPresent(profile -> {
                        String newFarmName = request.farmName() == null
                                ? profile.getFarmName()
                                : AccountIdentity.required(request.farmName(), "farmName", 150);
                        String newFarmLocation = user.getRegion() == null ? profile.getFarmLocation() : user.getRegion();
                        profile.updateFarm(newFarmName, newFarmLocation);
                    });
            case DRIVER -> driverProfileRepository.findByUserId(user.getId())
                    .ifPresent(profile -> {
                        if (request.vehicleType() != null || request.vehiclePlate() != null) {
                            String vehicleType = request.vehicleType() == null ? profile.getVehicleType()
                                    : AccountIdentity.required(request.vehicleType(), "vehicleType", 100);
                            String vehiclePlate = request.vehiclePlate() == null ? profile.getVehiclePlate()
                                    : AccountIdentity.required(request.vehiclePlate(), "vehiclePlate", 50);
                            profile.updateVehicle(vehicleType, vehiclePlate);
                        }
                        if (request.serviceArea() != null) {
                            profile.updateServiceArea(AccountIdentity.required(request.serviceArea(), "serviceArea", 150));
                        }
                    });
            default -> { }
        }
        return toProfile(user);
    }

    private void requireAdmin(AuthenticatedUser actor) {
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can perform this action");
        }
    }

    private void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedOperationException("The account is not active");
        }
    }

    private UserProfileResponse toProfile(User user) {
        UUID userId = user.getId();
        String businessName = null;
        String farmName = null;
        String farmLocation = null;
        String employeeCode = null;
        String licenseNumber = null;
        String vehicleType = null;
        String vehiclePlate = null;
        String serviceArea = null;

        switch (user.getRole()) {
            case BUYER, ADMIN -> { }
            case SUPPLIER -> businessName = supplierProfileRepository.findByUserId(userId)
                    .map(SupplierProfile::getBusinessName)
                    .orElse(null);
            case FARMER -> {
                Optional<FarmerProfile> profile = farmerProfileRepository.findByUserId(userId);
                farmName = profile.map(FarmerProfile::getFarmName).orElse(null);
                farmLocation = user.getRegion() == null ? profile.map(FarmerProfile::getFarmLocation).orElse(null) : user.getRegion();
            }
            case INSPECTOR -> employeeCode = inspectorProfileRepository.findByUserId(userId)
                    .map(InspectorProfile::getEmployeeCode)
                    .orElse(null);
            case DRIVER -> {
                Optional<DriverProfile> profile = driverProfileRepository.findByUserId(userId);
                licenseNumber = profile.map(DriverProfile::getLicenseNumber).orElse(null);
                vehicleType = profile.map(DriverProfile::getVehicleType).orElse(null);
                vehiclePlate = profile.map(DriverProfile::getVehiclePlate).orElse(null);
                serviceArea = profile.map(DriverProfile::getServiceArea).orElse(null);
            }
        }

        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getRegion(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getRole(),
                user.getStatus(),
                user.getVerificationStatus(),
                businessName,
                farmName,
                farmLocation,
                employeeCode,
                licenseNumber,
                vehicleType,
                vehiclePlate,
                serviceArea);
    }
}

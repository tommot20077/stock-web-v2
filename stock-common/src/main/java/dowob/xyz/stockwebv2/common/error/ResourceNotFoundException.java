package dowob.xyz.stockwebv2.common.error;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resourceName) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resourceName + " not found");
    }
}

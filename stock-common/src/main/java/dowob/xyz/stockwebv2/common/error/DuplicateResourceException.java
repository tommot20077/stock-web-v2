package dowob.xyz.stockwebv2.common.error;

public class DuplicateResourceException extends BusinessException {
    public DuplicateResourceException(String resourceName) {
        super(ErrorCode.DUPLICATE_RESOURCE, resourceName + " already exists");
    }
}

package org.zjmproject.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.zjmproject.pojo.Result;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(org.zjmproject.service.ParkingAdmission.Busy.class)
    public ResponseEntity<Result<?>> busy(Exception e){return ResponseEntity.status(503).body(Result.error(e.getMessage()));}
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, ConstraintViolationException.class})
    public Result<?> invalid(RuntimeException e) { return Result.error(e.getMessage()); }
    @ExceptionHandler(org.springframework.data.redis.RedisConnectionFailureException.class)
    public ResponseEntity<Result<?>> redisUnavailable(Exception e) { return ResponseEntity.status(503).body(Result.error("登录或预约服务暂不可用，请稍后重试")); }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,java.time.DateTimeException.class})
    public Result<?> malformed(Exception e) {return Result.error("请求参数格式无效");}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> validation(MethodArgumentNotValidException e) { return Result.error("参数校验失败"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<?> conflict(DataIntegrityViolationException e) { return Result.error("数据冲突或存在关联记录，请刷新后重试"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unexpected(Exception e) {
        Throwable root=e;
        String mapper="unknown";
        var pattern=java.util.regex.Pattern.compile("org\\.zjmproject\\.mapper\\.[A-Za-z0-9_]+\\.[A-Za-z0-9_]+(?![A-Za-z0-9_])");
        for(int depth=0;depth<32;depth++) {
            if(root.getMessage()!=null) {var match=pattern.matcher(root.getMessage());if(match.find()) mapper=match.group();}
            if(root.getCause()==null || root.getCause()==root) break;
            root=root.getCause();
        }
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String path=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet?servlet.getRequest().getRequestURI():"unknown";
        String origin=root.getStackTrace().length==0?"unknown":root.getStackTrace()[0].getClassName()+"#"+root.getStackTrace()[0].getMethodName();
        // Never log SQL parameters, exception messages, request bodies, tokens or third-party keys.
        log.error("Request failed requestId={} path={} mapper={} exception={} cause={} origin={}",org.slf4j.MDC.get("requestId"),path,mapper,e.getClass().getSimpleName(),root.getClass().getSimpleName(),origin);
        return ResponseEntity.internalServerError().body(Result.error("服务异常，请稍后重试"));
    }
}

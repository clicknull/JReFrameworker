package jreframeworker.annotations.types;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// this annotation is valid for types
@Target({ ElementType.TYPE })

// annotation will be recorded in the class file by the compiler,
// but won't be retained by the VM at run time (invisible annotation)
@Retention(RetentionPolicy.CLASS)

/**
 * Indicates the annotated type (class, abstract class, interface) should be 
 * purged from the runtime. Ignores all other JReFrameworker annotations.
 * 
 * @author Ben Holland
 */
public @interface PurgeType {
	int phase() default 1;
	String type();
}

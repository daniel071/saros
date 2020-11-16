package saros.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import saros.repackaged.picocontainer.BindKey;
import saros.repackaged.picocontainer.MutablePicoContainer;
import saros.repackaged.picocontainer.PicoContainer;
import saros.repackaged.picocontainer.annotations.Bind;

/**
 * This interface contains marker interfaces for binding components to specific keys.
 *
 * @see PicoContainer#getComponent(Object)
 * @see MutablePicoContainer#addComponent(Object, Object,
 *     saros.repackaged.picocontainer.Parameter...)
 * @see BindKey#bindKey(Class, Class)
 */
public interface IContextKeyBindings {
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Bind
  public @interface IBBStreamService {
    // marker interface
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Bind
  public @interface Socks5StreamService {
    // marker interface
  }

  /** The version number of the local Saros instance. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Bind
  public @interface SarosVersion {
    // marker interface
  }

  /** The qualifier used to identify the IDE-specific Saros implementation. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Bind
  public @interface SarosImplementation {
    // marker interface
  }

  /** The version number of the IDE Saros is running in. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Bind
  public @interface PlatformVersion {
    // marker interface
  }
}

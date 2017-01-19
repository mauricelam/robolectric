package org.robolectric;

import android.app.Application;
import android.os.Build;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.internal.AssumptionViolatedException;
import org.junit.internal.runners.model.EachTestNotifier;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.internal.DoNotInstrument;
import org.robolectric.annotation.internal.Instrument;
import org.robolectric.internal.DirectObjectMarker;
import org.robolectric.internal.GradleManifestFactory;
import org.robolectric.internal.IShadow;
import org.robolectric.internal.InstrumentingClassLoaderFactory;
import org.robolectric.internal.InvokeDynamic;
import org.robolectric.internal.ManifestFactory;
import org.robolectric.internal.ManifestIdentifier;
import org.robolectric.internal.MavenManifestFactory;
import org.robolectric.internal.ParallelUniverse;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.internal.SdkConfig;
import org.robolectric.internal.SdkEnvironment;
import org.robolectric.internal.ShadowProvider;
import org.robolectric.internal.ShadowedObject;
import org.robolectric.internal.bytecode.AndroidInterceptors;
import org.robolectric.internal.bytecode.ClassHandler;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.InstrumentationConfiguration.Builder;
import org.robolectric.internal.bytecode.InstrumentingClassLoader;
import org.robolectric.internal.bytecode.Interceptor;
import org.robolectric.internal.bytecode.Interceptors;
import org.robolectric.internal.bytecode.MethodRef;
import org.robolectric.internal.bytecode.RobolectricInternals;
import org.robolectric.internal.bytecode.ShadowInvalidator;
import org.robolectric.internal.bytecode.ShadowMap;
import org.robolectric.internal.bytecode.ShadowWrangler;
import org.robolectric.internal.dependency.CachedDependencyResolver;
import org.robolectric.internal.dependency.DependencyJar;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.internal.dependency.LocalDependencyResolver;
import org.robolectric.internal.dependency.MavenDependencyResolver;
import org.robolectric.internal.dependency.PropertiesDependencyResolver;
import org.robolectric.internal.fakes.RoboCharsets;
import org.robolectric.internal.fakes.RoboExtendedResponseCache;
import org.robolectric.internal.fakes.RoboResponseSource;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.res.PackageResourceTable;
import org.robolectric.res.ResourceMerger;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.ResourceTable;
import org.robolectric.res.ResourceTableFactory;
import org.robolectric.res.RoutingResourceTable;
import org.robolectric.res.builder.XmlBlock;
import org.robolectric.util.Logger;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.TempDirectory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Installs a {@link org.robolectric.internal.bytecode.InstrumentingClassLoader} and
 * {@link ResourceTable} in order to provide a simulation of the Android runtime environment.
 */
public class RobolectricTestRunner extends BlockJUnit4ClassRunner {

  public static final String CONFIG_PROPERTIES = "robolectric.properties";
  
  private static final Map<AndroidManifest, PackageResourceTable> appResourceTableCache = new HashMap<>();
  private static final Map<ManifestIdentifier, AndroidManifest> appManifestsCache = new HashMap<>();
  private static PackageResourceTable compiletimeSdkResourceTable;

  private final SdkPicker sdkPicker;
  private final ConfigMerger configMerger;
  private final Interceptors interceptors;

  private TestLifecycle<Application> testLifecycle;
  private DependencyResolver dependencyResolver;

  static {
    new SecureRandom(); // this starts up the Poller SunPKCS11-Darwin thread early, outside of any Robolectric classloader
  }

  private final HashSet<Class<?>> loadedTestClasses = new HashSet<>();

  /**
   * Creates a runner to run {@code testClass}. Looks in your working directory for your AndroidManifest.xml file
   * and res directory by default. Use the {@link Config} annotation to configure.
   *
   * @param testClass the test class to be run
   * @throws InitializationError if junit says so
   */
  public RobolectricTestRunner(final Class<?> testClass) throws InitializationError {
    super(testClass);
    this.configMerger = createConfigMerger();
    this.sdkPicker = createSdkPicker();
    this.interceptors = createInterceptors();
  }

  public static Builder withConfig(Builder builder, Config config) {
    for (Class<?> clazz : config.shadows()) {
      Implements annotation = clazz.getAnnotation(Implements.class);
      if (annotation == null) {
        throw new IllegalArgumentException(clazz + " is not annotated with @Implements");
      }

      String className = annotation.className();
      if (className.isEmpty()) {
        className = annotation.value().getName();
      }

      if (!className.isEmpty()) {
        builder.addInstrumentedClass(className);
      }
    }
    for (String packageName : config.instrumentedPackages()) {
      builder.addInstrumentedPackage(packageName);
    }
    return builder;
  }

  public static Builder configure(Builder builder, Interceptors interceptors) {
    for (MethodRef methodRef : interceptors.getAllMethodRefs()) {
      builder.addInterceptedMethod(methodRef);
    }

    builder.doNotAcquireClass(TestLifecycle.class)
      .doNotAcquireClass(ShadowWrangler.class)
      .doNotAcquireClass(AndroidManifest.class)
      .doNotAcquireClass(InstrumentingClassLoader.class)
      .doNotAcquireClass(SdkEnvironment.class)
      .doNotAcquireClass(SdkConfig.class)
      .doNotAcquireClass(RobolectricTestRunner.class)
      .doNotAcquireClass(HelperTestRunner.class)
      .doNotAcquireClass(ResourcePath.class)
      .doNotAcquireClass(ResourceTable.class)
      .doNotAcquireClass(XmlBlock.class)
      .doNotAcquireClass(ClassHandler.class)
      .doNotAcquireClass(ClassHandler.Plan.class)
      .doNotAcquireClass(ShadowInvalidator.class)
      .doNotAcquireClass(RealObject.class)
      .doNotAcquireClass(Implements.class)
      .doNotAcquireClass(Implementation.class)
      .doNotAcquireClass(Instrument.class)
      .doNotAcquireClass(DoNotInstrument.class)
      .doNotAcquireClass(Config.class)
      .doNotAcquireClass(IShadow.class)
      .doNotAcquireClass(Interceptor.class)
      .doNotAcquireClass(Interceptors.class)
      .doNotAcquireClass(ReflectionHelpers.class)
      .doNotAcquireClass(ReflectionHelpers.ClassParameter.class)
      .doNotAcquireClass(DirectObjectMarker.class)
      .doNotAcquireClass(DependencyJar.class)
      .doNotAcquireClass(ParallelUniverseInterface.class)
      .doNotAcquireClass(ShadowedObject.class)
      .doNotAcquireClass(TempDirectory.class);

    builder.doNotAcquirePackage("java.")
      .doNotAcquirePackage("javax.")
      .doNotAcquirePackage("sun.")
      .doNotAcquirePackage("com.sun.")
      .doNotAcquirePackage("org.w3c.")
      .doNotAcquirePackage("org.xml.")
      .doNotAcquirePackage("org.junit")
      .doNotAcquirePackage("org.hamcrest")
      .doNotAcquirePackage("org.specs2")  // allows for android projects with mixed scala\java tests to be
      .doNotAcquirePackage("scala.")      //  run with Maven Surefire (see the RoboSpecs project on github)
      .doNotAcquirePackage("kotlin.")
      .doNotAcquirePackage("com.almworks.sqlite4java"); // Fix #958: SQLite native library must be loaded once.

    builder.addClassNameTranslation("java.net.ExtendedResponseCache", RoboExtendedResponseCache.class.getName())
       .addClassNameTranslation("java.net.ResponseSource", RoboResponseSource.class.getName())
       .addClassNameTranslation("java.nio.charset.Charsets", RoboCharsets.class.getName());

    // Instrumenting these classes causes a weird failure.
    builder.doNotInstrumentClass("android.R")
        .doNotInstrumentClass("android.R$styleable");

    builder.addInstrumentedPackage("dalvik.")
      .addInstrumentedPackage("libcore.")
      .addInstrumentedPackage("android.")
      .addInstrumentedPackage("com.android.internal.")
      .addInstrumentedPackage("org.apache.http.")
      .addInstrumentedPackage("org.kxml2.");


    for (ShadowProvider provider : ServiceLoader.load(ShadowProvider.class)) {
      for (String packagePrefix : provider.getProvidedPackageNames()) {
        builder.addInstrumentedPackage(packagePrefix);
      }
    }
    return builder;
  }

  @SuppressWarnings("unchecked")
  private void assureTestLifecycle(SdkEnvironment sdkEnvironment) {
    try {
      ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
      testLifecycle = (TestLifecycle) robolectricClassLoader.loadClass(getTestLifecycleClass().getName()).newInstance();
    } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  protected DependencyResolver getJarResolver() {
    if (dependencyResolver == null) {
      if (Boolean.getBoolean("robolectric.offline")) {
        String dependencyDir = System.getProperty("robolectric.dependency.dir", ".");
        dependencyResolver = new LocalDependencyResolver(new File(dependencyDir));
      } else {
        File cacheDir = new File(new File(System.getProperty("java.io.tmpdir")), "robolectric");

        if (cacheDir.exists() || cacheDir.mkdir()) {
          Logger.info("Dependency cache location: %s", cacheDir.getAbsolutePath());
          dependencyResolver = new CachedDependencyResolver(new MavenDependencyResolver(), cacheDir, 60 * 60 * 24 * 1000);
        } else {
          dependencyResolver = new MavenDependencyResolver();
        }
      }

      URL buildPathPropertiesUrl = getClass().getClassLoader().getResource("robolectric-deps.properties");
      if (buildPathPropertiesUrl != null) {
        Logger.info("Using Robolectric classes from %s", buildPathPropertiesUrl.getPath());

        FsFile propertiesFile = Fs.fileFromPath(buildPathPropertiesUrl.getFile());
        try {
          dependencyResolver = new PropertiesDependencyResolver(propertiesFile, dependencyResolver);
        } catch (IOException e) {
          throw new RuntimeException("couldn't read " + buildPathPropertiesUrl, e);
        }
      }
    }

    return dependencyResolver;
  }

  /**
   * Create a {@link ClassHandler} appropriate for the given arguments.
   *
   * Robolectric may chose to cache the returned instance, keyed by <tt>shadowMap</tt> and <tt>sdkConfig</tt>.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @param shadowMap the {@link ShadowMap} in effect for this test
   * @param sdkConfig the {@link SdkConfig} in effect for this test
   * @return an appropriate {@link ClassHandler}. This implementation returns a {@link ShadowWrangler}.
   * @since 2.3
   */
  @NotNull
  protected ClassHandler createClassHandler(ShadowMap shadowMap, SdkConfig sdkConfig) {
    return new ShadowWrangler(shadowMap, sdkConfig.getApiLevel(), interceptors);
  }

  /**
   * Create a {@link ConfigMerger} for calculating the {@link Config} tests.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @return an {@link ConfigMerger}.
   * @since 3.2
   */
  @NotNull
  private ConfigMerger createConfigMerger() {
    return new ConfigMerger();
  }

  /**
   * Create a {@link SdkPicker} for determining which SDKs will be tested.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @return an {@link SdkPicker}.
   * @since 3.2
   */
  @NotNull
  protected SdkPicker createSdkPicker() {
    return new SdkPicker();
  }

  @NotNull // todo
  private Interceptors createInterceptors() {
    return new AndroidInterceptors().build();
  }

  /**
   * Create an {@link InstrumentationConfiguration} suitable for the provided {@link Config}.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @param config the merged configuration for the test that's about to run
   * @return an {@link InstrumentationConfiguration}
   */
  @NotNull
  public InstrumentationConfiguration createClassLoaderConfig(Config config) {
    Builder builder = InstrumentationConfiguration.newBuilder();
    builder = withConfig(builder, config);
    return configure(builder, interceptors).build();
  }

  /**
   * An instance of the returned class will be created for each test invocation.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @return a class which implements {@link TestLifecycle}. This implementation returns a {@link DefaultTestLifecycle}.
   */
  @NotNull
  protected Class<? extends TestLifecycle> getTestLifecycleClass() {
    return DefaultTestLifecycle.class;
  }

  public static void injectEnvironment(ClassLoader robolectricClassLoader,
      ClassHandler classHandler, ShadowInvalidator invalidator) {
    String className = RobolectricInternals.class.getName();
    Class<?> robolectricInternalsClass = ReflectionHelpers.loadClass(robolectricClassLoader, className);
    ReflectionHelpers.setStaticField(robolectricInternalsClass, "classHandler", classHandler);
    ReflectionHelpers.setStaticField(robolectricInternalsClass, "shadowInvalidator", invalidator);
  }

  @Override
  protected Statement classBlock(RunNotifier notifier) {
    final Statement statement = childrenInvoker(notifier);
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          statement.evaluate();
          for (Class<?> testClass : loadedTestClasses) {
            invokeAfterClass(testClass);
          }
        } finally {
          afterClass();
          loadedTestClasses.clear();
        }
      }
    };
  }

  @Override
  protected List<FrameworkMethod> getChildren() {
    List<FrameworkMethod> children = new ArrayList<>();
    for (FrameworkMethod frameworkMethod : super.getChildren()) {
      try {
        Config config = getConfig(frameworkMethod.getMethod());
        AndroidManifest appManifest = getAppManifest(config);
        List<SdkConfig> sdksToRun = sdkPicker.selectSdks(config, appManifest);
        RobolectricFrameworkMethod last = null;
        for (SdkConfig sdkConfig : sdksToRun) {
          last = new RobolectricFrameworkMethod(frameworkMethod.getMethod(), appManifest, sdkConfig, config);
          children.add(last);
        }
        if (last != null) {
          last.dontIncludeApiLevelInName();
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("failed to configure " +
            getTestClass().getName() + "." + frameworkMethod.getMethod().getName() +
            ": " + e.getMessage(), e);
      }
    }
    return children;
  }

  private static void invokeAfterClass(final Class<?> clazz) throws Throwable {
    final TestClass testClass = new TestClass(clazz);
    final List<FrameworkMethod> afters = testClass.getAnnotatedMethods(AfterClass.class);
    for (FrameworkMethod after : afters) {
      after.invokeExplosively(null);
    }
  }

  @Override
  protected void runChild(FrameworkMethod method, RunNotifier notifier) {
    RobolectricFrameworkMethod roboMethod = (RobolectricFrameworkMethod) method;
    Description description = describeChild(method);
    EachTestNotifier eachNotifier = new EachTestNotifier(notifier, description);

    Config config = roboMethod.config;
    if (shouldIgnore(method, config)) {
      eachNotifier.fireTestIgnored();
    } else {
      eachNotifier.fireTestStarted();
      try {
        SdkConfig sdkConfig = ((RobolectricFrameworkMethod) method).sdkConfig;
        InstrumentingClassLoaderFactory instrumentingClassLoaderFactory =
            new InstrumentingClassLoaderFactory(createClassLoaderConfig(config), getJarResolver(), interceptors);
        SdkEnvironment sdkEnvironment = instrumentingClassLoaderFactory.getSdkEnvironment(sdkConfig);
        methodBlock(method, config, roboMethod.getAppManifest(), sdkEnvironment).evaluate();
      } catch (AssumptionViolatedException e) {
        eachNotifier.addFailedAssumption(e);
      } catch (Throwable e) {
        eachNotifier.addFailure(e);
      } finally {
        eachNotifier.fireTestFinished();
      }
    }
  }

  /**
   * Returns the ResourceProvider for the compile time SDK.
   */
  @NotNull
  private static PackageResourceTable getCompiletimeSdkResourceTable() {
    if (compiletimeSdkResourceTable == null) {
      compiletimeSdkResourceTable = ResourceTableFactory.newResourceTable("android", new ResourcePath(android.R.class, null, null));
    }
    return compiletimeSdkResourceTable;
  }

  protected boolean shouldIgnore(FrameworkMethod method, Config config) {
    return method.getAnnotation(Ignore.class) != null;
  }

  private ParallelUniverseInterface parallelUniverseInterface;

  Statement methodBlock(final FrameworkMethod method, final Config config, final AndroidManifest appManifest, final SdkEnvironment sdkEnvironment) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        // Configure shadows *BEFORE* setting the ClassLoader. This is necessary because
        // creating the ShadowMap loads all ShadowProviders via ServiceLoader and this is
        // not available once we install the Robolectric class loader.
        configureShadows(sdkEnvironment, config);

        Thread.currentThread().setContextClassLoader(sdkEnvironment.getRobolectricClassLoader());

        Class bootstrappedTestClass = sdkEnvironment.bootstrappedClass(getTestClass().getJavaClass());
        HelperTestRunner helperTestRunner = getHelperTestRunner(bootstrappedTestClass);

        final Method bootstrappedMethod;
        try {
          //noinspection unchecked
          bootstrappedMethod = bootstrappedTestClass.getMethod(method.getMethod().getName());
        } catch (NoSuchMethodException e) {
          throw new RuntimeException(e);
        }

        parallelUniverseInterface = getHooksInterface(sdkEnvironment);
        try {
          try {
            // Only invoke @BeforeClass once per class
            if (!loadedTestClasses.contains(bootstrappedTestClass)) {
              invokeBeforeClass(bootstrappedTestClass);
            }
            assureTestLifecycle(sdkEnvironment);

            parallelUniverseInterface.setSdkConfig(sdkEnvironment.getSdkConfig());
            parallelUniverseInterface.resetStaticState(config);

            SdkConfig sdkConfig = ((RobolectricFrameworkMethod) method).sdkConfig;
            Class<?> androidBuildVersionClass = sdkEnvironment.bootstrappedClass(Build.VERSION.class);
            ReflectionHelpers.setStaticField(androidBuildVersionClass, "SDK_INT", sdkConfig.getApiLevel());
            ReflectionHelpers.setStaticField(androidBuildVersionClass, "RELEASE", sdkConfig.getAndroidVersion());

            PackageResourceTable systemResourceTable = sdkEnvironment.getSystemResourceTable(getJarResolver());
            PackageResourceTable appResourceTable = getAppResourceTable(appManifest);

            parallelUniverseInterface.setUpApplicationState(bootstrappedMethod, testLifecycle, appManifest, config, new RoutingResourceTable(getCompiletimeSdkResourceTable(), appResourceTable), new RoutingResourceTable(systemResourceTable, appResourceTable), new RoutingResourceTable(systemResourceTable));
            testLifecycle.beforeTest(bootstrappedMethod);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }

          final Statement statement = helperTestRunner.methodBlock(new FrameworkMethod(bootstrappedMethod));

          // todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
          try {
            statement.evaluate();
          } finally {
            try {
              parallelUniverseInterface.tearDownApplication();
            } finally {
              try {
                internalAfterTest(bootstrappedMethod);
              } finally {
                parallelUniverseInterface.resetStaticState(config); // afterward too, so stuff doesn't hold on to classes?
              }
            }
          }
        } finally {
          Thread.currentThread().setContextClassLoader(RobolectricTestRunner.class.getClassLoader());
          parallelUniverseInterface = null;
        }
      }
    };
  }

  private void invokeBeforeClass(final Class clazz) throws Throwable {
    if (!loadedTestClasses.contains(clazz)) {
      loadedTestClasses.add(clazz);

      final TestClass testClass = new TestClass(clazz);
      final List<FrameworkMethod> befores = testClass.getAnnotatedMethods(BeforeClass.class);
      for (FrameworkMethod before : befores) {
        before.invokeExplosively(null);
      }
    }
  }

  protected HelperTestRunner getHelperTestRunner(Class bootstrappedTestClass) {
    try {
      return new HelperTestRunner(bootstrappedTestClass);
    } catch (InitializationError initializationError) {
      throw new RuntimeException(initializationError);
    }
  }

  /**
   * Detects which build system is in use and returns the appropriate ManifestFactory implementation.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @param config Specification of the SDK version, manifest file, package name, etc.
   */
  protected ManifestFactory getManifestFactory(Config config) {
    Class<?> buildConstants = config.constants();
    //noinspection ConstantConditions
    if (buildConstants != null && buildConstants != Void.class) {
      return new GradleManifestFactory();
    } else {
      return new MavenManifestFactory();
    }
  }

  protected AndroidManifest getAppManifest(Config config) {
    ManifestFactory manifestFactory = getManifestFactory(config);
    ManifestIdentifier identifier = manifestFactory.identify(config);

    synchronized (appManifestsCache) {
      AndroidManifest appManifest;
      appManifest = appManifestsCache.get(identifier);
      if (appManifest == null) {
        appManifest = manifestFactory.create(identifier);
        appManifestsCache.put(identifier, appManifest);
      }

      return appManifest;
    }
  }

  /**
   * Compute the effective Robolectric configuration for a given test method.
   *
   * Configuration information is collected from package-level <tt>robolectric.properties</tt> files
   * and {@link Config} annotations on test classes, superclasses, and methods.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @param method the test method
   * @return the effective Robolectric configuration for the given test method
   * @since 2.0
   */
  public Config getConfig(Method method) {
    return configMerger.getConfig(getTestClass().getJavaClass(), method, buildGlobalConfig());
  }

  /**
   * Provides the base Robolectric configuration {@link Config} used for all tests.
   *
   * Configuration provided for specific packages, test classes, and test method
   * configurations will override values provided here.
   *
   * Custom TestRunner subclasses may wish to override this method to provide
   * alternate configuration. Consider using a {@link Config.Builder}.
   *
   * The default implementation has appropriate values for most use cases.
   *
   * @return global {@link Config} object
   * @since 3.1.3
   */
  protected Config buildGlobalConfig() {
    return new Config.Builder().build();
  }

  protected void configureShadows(SdkEnvironment sdkEnvironment, Config config) {
    ShadowMap shadowMap = createShadowMap();

    if (config != null) {
      Class<?>[] shadows = config.shadows();
      if (shadows.length > 0) {
        shadowMap = shadowMap.newBuilder().addShadowClasses(shadows).build();
      }
    }

    if (InvokeDynamic.ENABLED) {
      ShadowMap oldShadowMap = sdkEnvironment.replaceShadowMap(shadowMap);
      Set<String> invalidatedClasses = shadowMap.getInvalidatedClasses(oldShadowMap);
      sdkEnvironment.getShadowInvalidator().invalidateClasses(invalidatedClasses);
    }

    ClassHandler classHandler = createClassHandler(shadowMap, sdkEnvironment.getSdkConfig());
    injectEnvironment(sdkEnvironment.getRobolectricClassLoader(), classHandler, sdkEnvironment.getShadowInvalidator());
  }

  ParallelUniverseInterface getHooksInterface(SdkEnvironment sdkEnvironment) {
    ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
    try {
      Class<?> clazz = robolectricClassLoader.loadClass(ParallelUniverse.class.getName());
      Class<? extends ParallelUniverseInterface> typedClazz = clazz.asSubclass(ParallelUniverseInterface.class);
      Constructor<? extends ParallelUniverseInterface> constructor = typedClazz.getConstructor(RobolectricTestRunner.class);
      return constructor.newInstance(this);
    } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  public void internalAfterTest(final Method method) {
    testLifecycle.afterTest(method);
  }

  private void afterClass() {
    testLifecycle = null;
  }

  @TestOnly
  boolean allStateIsCleared() {
    return testLifecycle == null;
  }

  @Override
  public Object createTest() throws Exception {
    throw new UnsupportedOperationException("this should always be invoked on the HelperTestRunner!");
  }

  private final PackageResourceTable getAppResourceTable(final AndroidManifest appManifest) {
    PackageResourceTable resourceTable = appResourceTableCache.get(appManifest);
    if (resourceTable == null) {
      resourceTable = ResourceMerger.buildResourceTable(appManifest);

      appResourceTableCache.put(appManifest, resourceTable);
    }
    return resourceTable;
  }

  protected ShadowMap createShadowMap() {
    return ShadowMap.EMPTY;
  }

  public class HelperTestRunner extends BlockJUnit4ClassRunner {
    public HelperTestRunner(Class<?> testClass) throws InitializationError {
      super(testClass);
    }

    @Override protected Object createTest() throws Exception {
      Object test = super.createTest();
      testLifecycle.prepareTest(test);
      return test;
    }

    @Override public Statement classBlock(RunNotifier notifier) {
      return super.classBlock(notifier);
    }

    @Override public Statement methodBlock(FrameworkMethod method) {
      return super.methodBlock(method);
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
      final Statement invoker = super.methodInvoker(method, test);
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          Thread orig = parallelUniverseInterface.getMainThread();
          parallelUniverseInterface.setMainThread(Thread.currentThread());
          try {
            invoker.evaluate();
          } finally {
            parallelUniverseInterface.setMainThread(orig);
          }
        }
      };
    }
  }

  static class RobolectricFrameworkMethod extends FrameworkMethod {
    private final @NotNull AndroidManifest appManifest;
    final @NotNull SdkConfig sdkConfig;
    final @NotNull Config config;
    private boolean includeApiLevelInName = true;

    RobolectricFrameworkMethod(@NotNull Method method, @NotNull AndroidManifest appManifest, @NotNull SdkConfig sdkConfig, @NotNull Config config) {
      super(method);
      this.appManifest = appManifest;
      this.sdkConfig = sdkConfig;
      this.config = config;
    }

    @Override
    public String getName() {
      // IDE focused test runs rely on preservation of the test name; we'll use the
      //   latest supported SDK for focused test runs
      return super.getName() +
          (includeApiLevelInName ? "[" + sdkConfig.getApiLevel() + "]" : "");
    }

    void dontIncludeApiLevelInName() {
      includeApiLevelInName = false;
    }

    @NotNull
    public AndroidManifest getAppManifest() {
      return appManifest;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      if (!super.equals(o)) return false;

      RobolectricFrameworkMethod that = (RobolectricFrameworkMethod) o;

      return sdkConfig.equals(that.sdkConfig);
    }

    @Override
    public int hashCode() {
      int result = super.hashCode();
      result = 31 * result + sdkConfig.hashCode();
      return result;
    }
  }
}
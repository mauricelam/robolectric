package org.robolectric.shadows;

import android.app.Activity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.TestRunners;
import org.robolectric.internal.ShadowExtractor;
import org.robolectric.shadow.api.Shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.robolectric.Shadows.shadowOf;

@RunWith(TestRunners.SelfTest.class)
public class ShadowTest {

  private ClassLoader myClassLoader;

  @Before
  public void setUp() throws Exception {
    myClassLoader = getClass().getClassLoader();
  }

  @Test
  public void newInstanceOf() throws Exception {
    assertThat(Shadow.newInstanceOf(Activity.class.getName()).getClass().getClassLoader())
        .isSameAs(myClassLoader);
  }

  @Test
  public void deprecated_newInstanceOf() throws Exception {
    assertThat(org.robolectric.internal.Shadow.newInstanceOf(Activity.class.getName()).getClass().getClassLoader())
        .isSameAs(myClassLoader);
  }

  @Test
  public void extractor() throws Exception {
    Activity activity = new Activity();
    assertThat((ShadowActivity) Shadow.extract(activity)).isSameAs(shadowOf(activity));
  }

  @Test
  public void deprecated_extractor() throws Exception {
    Activity activity = new Activity();
    assertThat((ShadowActivity) org.robolectric.internal.Shadow.extract(activity))
        .isSameAs(shadowOf(activity));
  }

  @Test
  public void otherDeprecated_extractor() throws Exception {
    Activity activity = new Activity();
    assertThat(ShadowExtractor.extract(activity)).isSameAs(shadowOf(activity));
  }
}

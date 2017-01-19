package org.robolectric.shadows.support.v4;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.TestRunnerWithManifest;
import android.support.v4.content.AsyncTaskLoader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(TestRunnerWithManifest.class)
public class ShadowAsyncTaskLoaderTest {
  private final List<String> transcript = new ArrayList<>();

  @Before
  public void setUp() {
    Robolectric.getForegroundThreadScheduler().pause();
    Robolectric.getBackgroundThreadScheduler().pause();
  }

  @Test
  public void forceLoad_shouldEnqueueWorkOnSchedulers() {
    new TestLoader(42).forceLoad();
    assertThat(transcript).isEmpty();

    Robolectric.flushBackgroundThreadScheduler();
    assertThat(transcript).containsExactly("loadInBackground");
    transcript.clear();

    Robolectric.flushForegroundThreadScheduler();
    assertThat(transcript).containsExactly("deliverResult 42");
  }

  public class TestLoader extends AsyncTaskLoader<Integer> {
    private final Integer data;

    public TestLoader(Integer data) {
      super(RuntimeEnvironment.application);
      this.data = data;
    }

    @Override
    public Integer loadInBackground() {
      transcript.add("loadInBackground");
      return data;
    }

    @Override
    public void deliverResult(Integer data) {
      transcript.add("deliverResult " + data.toString());
    }
  }
}

package io.sweers.catchup.ui.controllers;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import com.bluelinelabs.conductor.Controller;
import com.squareup.moshi.Moshi;
import com.uber.autodispose.CompletableScoper;
import com.uber.autodispose.ObservableScoper;
import dagger.Binds;
import dagger.Lazy;
import dagger.Provides;
import dagger.Subcomponent;
import dagger.android.AndroidInjector;
import dagger.multibindings.IntoMap;
import io.reactivex.Single;
import io.sweers.catchup.R;
import io.sweers.catchup.data.EpochInstantJsonAdapter;
import io.sweers.catchup.data.LinkManager;
import io.sweers.catchup.data.hackernews.HackerNewsService;
import io.sweers.catchup.data.hackernews.model.HackerNewsStory;
import io.sweers.catchup.injection.ControllerKey;
import io.sweers.catchup.injection.qualifiers.ForApi;
import io.sweers.catchup.injection.scopes.PerController;
import io.sweers.catchup.ui.base.BaseNewsController;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.threeten.bp.Instant;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.moshi.MoshiConverterFactory;

public final class HackerNewsController extends BaseNewsController<HackerNewsStory> {

  @Inject HackerNewsService service;
  @Inject LinkManager linkManager;

  public HackerNewsController() {
    super();
  }

  public HackerNewsController(Bundle args) {
    super(args);
  }

  @Override protected Context onThemeContext(@NonNull Context context) {
    return new ContextThemeWrapper(context, R.style.CatchUp_HackerNews);
  }

  @Override
  protected void bindItemView(@NonNull HackerNewsStory story, @NonNull ViewHolder holder) {
    holder.title(story.title());
    holder.score(Pair.create("+", story.score()));
    holder.timestamp(story.time());
    holder.author(story.by());

    String url = story.url();
    if (url == null) {
      holder.source(null);
    } else {
      holder.source(HttpUrl.parse(url)
          .host());
    }

    int commentsCount = 0;
    // TODO Adapter to coerce this to Collections.emptyList()?
    List<String> kids = story.kids();
    if (kids != null) {
      commentsCount = kids.size();
    }
    holder.comments(commentsCount);
    holder.tag(null);

    if (!TextUtils.isEmpty(url)) {
      holder.itemLongClicks()
          .to(new ObservableScoper<>(holder))
          .subscribe(SmmryController.showFor(this, url));
    }

    holder.itemClicks()
        .compose(transformUrlToMeta(url))
        .flatMapCompletable(linkManager)
        .to(new CompletableScoper(holder))
        .subscribe();

    holder.itemCommentClicks()
        .compose(transformUrlToMeta("https://news.ycombinator.com/item?id=" + story.id()))
        .flatMapCompletable(linkManager)
        .to(new CompletableScoper(holder))
        .subscribe();
  }

  @NonNull @Override protected Single<List<HackerNewsStory>> getDataSingle() {
    return service.topStories()
        .flattenAsObservable(strings -> strings)
        .take(50) // TODO Pref this
        .concatMapEager(id -> service.getItem(id)
            .toObservable())
        .toList();
  }

  @PerController
  @Subcomponent
  public interface Component extends AndroidInjector<HackerNewsController> {

    @Subcomponent.Builder
    abstract class Builder extends AndroidInjector.Builder<HackerNewsController> {}
  }

  @dagger.Module(subcomponents = Component.class)
  public abstract static class Module {

    @Binds @IntoMap @ControllerKey(HackerNewsController.class)
    abstract AndroidInjector.Factory<? extends Controller> bindHackerNewsControllerInjectorFactory(
        Component.Builder builder);

    @ForApi @Provides @PerController
    static OkHttpClient provideHackerNewsOkHttpClient(OkHttpClient client) {
      return client.newBuilder()
          .addNetworkInterceptor(chain -> {
            Request request = chain.request();
            HttpUrl url = request.url();
            request = request.newBuilder()
                .url(url.newBuilder()
                    .encodedPath(url.encodedPath() + ".json")
                    .build())
                .build();
            Response originalResponse = chain.proceed(request);
            // Hacker News requests are expensive and take awhile, so cache for 5min
            int maxAge = 60 * 5;
            return originalResponse.newBuilder()
                .header("Cache-Control", "public, max-age=" + maxAge)
                .build();
          })
          .build();
    }

    @Provides @PerController @ForApi static Moshi provideHackerNewsMoshi(Moshi moshi) {
      return moshi.newBuilder()
          .add(Instant.class, new EpochInstantJsonAdapter(TimeUnit.SECONDS))
          .build();
    }

    @Provides @PerController
    static HackerNewsService provideHackerNewsService(@ForApi final Lazy<OkHttpClient> client,
        @ForApi Moshi moshi,
        RxJava2CallAdapterFactory rxJavaCallAdapterFactory) {
      Retrofit retrofit = new Retrofit.Builder().baseUrl(HackerNewsService.ENDPOINT)
          .callFactory(request -> client.get()
              .newCall(request))
          .addCallAdapterFactory(rxJavaCallAdapterFactory)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .build();
      return retrofit.create(HackerNewsService.class);
    }
  }
}

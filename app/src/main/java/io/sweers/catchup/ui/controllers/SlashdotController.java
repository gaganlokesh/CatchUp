package io.sweers.catchup.ui.controllers;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.ContextThemeWrapper;
import com.bluelinelabs.conductor.Controller;
import com.uber.autodispose.CompletableScoper;
import dagger.Binds;
import dagger.Lazy;
import dagger.Provides;
import dagger.Subcomponent;
import dagger.android.AndroidInjector;
import dagger.multibindings.IntoMap;
import io.reactivex.Single;
import io.sweers.catchup.R;
import io.sweers.catchup.data.LinkManager;
import io.sweers.catchup.data.slashdot.Entry;
import io.sweers.catchup.data.slashdot.SlashdotService;
import io.sweers.catchup.injection.ControllerKey;
import io.sweers.catchup.injection.qualifiers.ForApi;
import io.sweers.catchup.injection.scopes.PerController;
import io.sweers.catchup.ui.base.BaseNewsController;
import io.sweers.catchup.util.Iso8601Utils;
import java.util.List;
import javax.inject.Inject;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.simplexml.SimpleXmlConverterFactory;

public final class SlashdotController extends BaseNewsController<Entry> {

  @Inject SlashdotService service;
  @Inject LinkManager linkManager;

  public SlashdotController() {
    super();
  }

  public SlashdotController(Bundle args) {
    super(args);
  }

  @Override protected Context onThemeContext(@NonNull Context context) {
    return new ContextThemeWrapper(context, R.style.CatchUp_Slashdot);
  }

  @Override protected void bindItemView(@NonNull Entry entry, @NonNull ViewHolder holder) {
    holder.title(entry.title);

    holder.score(null);
    holder.timestamp(Iso8601Utils.parse(entry.updated));
    holder.author(entry.author.name);

    holder.source(entry.department);

    holder.comments(entry.comments);
    holder.tag(entry.section);

    holder.itemClicks()
        .compose(transformUrlToMeta(entry.id))
        .flatMapCompletable(linkManager)
        .to(new CompletableScoper(holder))
        .subscribe();
    holder.itemCommentClicks()
        .compose(transformUrlToMeta(entry.id + "#comments"))
        .flatMapCompletable(linkManager)
        .to(new CompletableScoper(holder))
        .subscribe();
  }

  @NonNull @Override protected Single<List<Entry>> getDataSingle() {
    return service.main()
        .map(channel -> channel.itemList);
  }

  @PerController
  @Subcomponent
  public interface Component extends AndroidInjector<SlashdotController> {

    @Subcomponent.Builder
    abstract class Builder extends AndroidInjector.Builder<SlashdotController> {}
  }

  @dagger.Module(subcomponents = Component.class)
  public abstract static class Module {

    @Binds @IntoMap @ControllerKey(SlashdotController.class)
    abstract AndroidInjector.Factory<? extends Controller> bindSlashdotControllerInjectorFactory(
        Component.Builder builder);

    @Provides @ForApi @PerController
    static OkHttpClient provideSlashdotOkHttpClient(OkHttpClient okHttpClient) {
      return okHttpClient.newBuilder()
          .addNetworkInterceptor(chain -> {
            Response originalResponse = chain.proceed(chain.request());
            // read from cache for 30 minutes, per slashdot's preferred limit
            int maxAge = 60 * 30;
            return originalResponse.newBuilder()
                .header("Cache-Control", "public, max-age=" + maxAge)
                .build();
          })
          .build();
    }

    @Provides @PerController
    static SlashdotService provideSlashdotService(@ForApi final Lazy<OkHttpClient> client,
        RxJava2CallAdapterFactory rxJavaCallAdapterFactory) {
      Retrofit retrofit = new Retrofit.Builder().baseUrl(SlashdotService.ENDPOINT)
          .callFactory(request -> client.get()
              .newCall(request))
          .addCallAdapterFactory(rxJavaCallAdapterFactory)
          .addConverterFactory(SimpleXmlConverterFactory.createNonStrict())
          .build();
      return retrofit.create(SlashdotService.class);
    }
  }
}

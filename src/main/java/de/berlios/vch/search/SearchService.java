package de.berlios.vch.search;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.felix.ipojo.annotations.Bind;
import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Unbind;
import org.apache.felix.ipojo.annotations.Validate;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceException;
import org.osgi.service.log.LogService;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.i18n.ResourceBundleLoader;
import de.berlios.vch.i18n.ResourceBundleProvider;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.uri.IVchUriResolver;

@Component
@Provides
public class SearchService implements ISearchService, IVchUriResolver, ResourceBundleProvider {

    @Requires
    private LogService logger;

    private Set<ISearchProvider> searchProviders = new HashSet<ISearchProvider>();

    private ResourceBundle resourceBundle;

    private BundleContext ctx;

    public SearchService(BundleContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public IOverviewPage search(final String query) {
        if (query == null || query.length() < 3) {
            throw new IllegalArgumentException("Query is too short. Enter at least 3 characters.");
        }

        logger.log(LogService.LOG_DEBUG, "Searching for \"" + query + "\"");
        final IOverviewPage result = new OverviewPage();
        result.setParser("search");
        result.setTitle(getResourceBundle().getString("search_results"));

        // create a task for each search provider and execute it with the thread pool
        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (final ISearchProvider searchProvider : searchProviders) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        IOverviewPage r = searchProvider.search(query);
                        r.setTitle(searchProvider.getName());

                        // add this provider to the result set, if we have at least one hit
                        if (!r.getPages().isEmpty()) {
                            result.getPages().add(r);
                        }
                    } catch (Exception e) {
                        logger.log(LogService.LOG_ERROR, "Error occured while searching with "
                                + searchProvider.getClass().getName()
                                + ". No results will be available from this provider.", e);
                    }
                }
            });
        }

        // wait for all search tasks to finish, but wait at most x seconds
        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
            executor.shutdownNow();
        } catch (InterruptedException e) {
            logger.log(LogService.LOG_WARNING, "Couldn't stop search thread pool", e);
        }

        return result;
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        for (ISearchProvider searchProvider : searchProviders) {
            String id = page.getParser();
            if (searchProvider.getId().equals(id)) {
                IWebPage parsedPage = searchProvider.parse(page);
                if (parsedPage instanceof IVideoPage) {
                    setVchUri(parsedPage);
                }
                return parsedPage;
            }
        }

        throw new ServiceException("No SearchProvider found for " + page.getUri());
    }

    @Override
    public boolean accept(URI vchuri) {
        return "vchsearch".equals(vchuri.getScheme());
    }

    @Override
    public IWebPage resolve(URI vchuri) throws Exception {
        if (!"vchsearch".equals(vchuri.getScheme())) {
            throw new IllegalArgumentException("URI for this resolver has to have the scheme vchsearch://");
        }

        logger.log(LogService.LOG_DEBUG, "Resolve VCH URI: " + vchuri);
        String parser = vchuri.getPath().substring(1);
        String query = vchuri.getRawQuery();
        logger.log(LogService.LOG_DEBUG, "Resolve query: " + query);
        Map<String, List<String>> params = HttpUtils.parseQuery(query);

        IWebPage page = null;
        if (params.containsKey("duration")) {
            IVideoPage vpage = new VideoPage();
            vpage.setDuration(Long.parseLong(params.get("duration").get(0)));
            if (params.containsKey("videoUri")) {
                vpage.setVideoUri(new URI(params.get("videoUri").get(0)));
            }
            if (params.containsKey("thumbUri")) {
                vpage.setThumbnail(new URI(params.get("thumbUri").get(0)));
            }
            if (params.containsKey("desc")) {
                vpage.setDescription(params.get("desc").get(0));
            }
            if (params.containsKey("pubdate")) {
                Calendar pubDate = Calendar.getInstance();
                pubDate.setTimeInMillis(Long.parseLong(params.get("pubdate").get(0)));
                vpage.setPublishDate(pubDate);
            }
            page = vpage;
        } else {
            page = new OverviewPage();
        }
        for (Iterator<Entry<String, List<String>>> iterator = params.entrySet().iterator(); iterator.hasNext();) {
            Entry<String, List<String>> entry = iterator.next();
            if (entry.getKey().startsWith("user.")) {
                page.getUserData().put(entry.getKey().substring(5), entry.getValue().get(0));
            }
        }
        page.setParser(parser);
        page.setTitle(params.get("title").get(0));
        return page;
    }

    private void setVchUri(IWebPage page) throws Exception {
        if (page instanceof IOverviewPage) {
            IOverviewPage opage = (IOverviewPage) page;
            for (IWebPage wpage : opage.getPages()) {
                setVchUri(wpage);
            }
        } else if (page instanceof IVideoPage) {
            URI vchuri = createVchUri(page);
            page.setVchUri(vchuri);
        }
    }

    private URI createVchUri(IWebPage page) throws UnsupportedEncodingException, URISyntaxException {
        String charset = "UTF-8";
        String vchuri = "vchsearch://localhost/" + page.getParser();
        vchuri += "?title=" + URLEncoder.encode(page.getTitle(), charset);
        vchuri += "&uri=" + URLEncoder.encode(page.getUri().toString(), charset);
        if (page instanceof IVideoPage) {
            IVideoPage video = (IVideoPage) page;
            vchuri += "&duration=" + video.getDuration();
            if (video.getVideoUri() != null) {
                vchuri += "&videoUri=" + URLEncoder.encode(video.getVideoUri().toString(), charset);
                logger.log(LogService.LOG_DEBUG,
                        "Video URI: " + URLEncoder.encode(video.getVideoUri().toString(), charset));
            }
            if (video.getThumbnail() != null) {
                vchuri += "&thumbUri=" + URLEncoder.encode(video.getThumbnail().toString(), charset);
            }
            if (video.getDescription() != null) {
                vchuri += "&desc=" + URLEncoder.encode(video.getDescription(), charset);
            }
            if (video.getPublishDate() != null) {
                vchuri += "&pubdate=" + video.getPublishDate().getTimeInMillis();
            }
        }
        for (Entry<String, Object> entry : page.getUserData().entrySet()) {
            vchuri += "&user." + URLEncoder.encode(entry.getKey().toString(), charset) + "="
                    + URLEncoder.encode(entry.getValue().toString(), charset);
        }
        return new URI(vchuri);
    }

    @Override
    public ResourceBundle getResourceBundle() {
        if (resourceBundle == null) {
            try {
                logger.log(LogService.LOG_DEBUG, "Loading resource bundle for " + getClass().getSimpleName());
                resourceBundle = ResourceBundleLoader.load(ctx, Locale.getDefault());
            } catch (IOException e) {
                logger.log(LogService.LOG_ERROR, "Couldn't load resource bundle", e);
            }
        }
        return resourceBundle;
    }

    // ############ ipojo stuff #########################################

    // validate and invalidate methods seem to be necessary for the bind methods to work
    @Validate
    public void start() {
    }

    @Invalidate
    public void stop() {
    }

    @Bind(id = "searchProviders", aggregate = true)
    public synchronized void addProvider(ISearchProvider provider) {
        logger.log(LogService.LOG_INFO, "Adding search provider " + provider.getClass().getName());
        searchProviders.add(provider);
        logger.log(LogService.LOG_INFO, searchProviders.size() + " search providers available");
    }

    @Unbind(id = "searchProviders", aggregate = true)
    public synchronized void removeProvider(ISearchProvider provider) {
        logger.log(LogService.LOG_INFO, "Removing search provider " + provider.getClass().getName());
        searchProviders.remove(provider);
        logger.log(LogService.LOG_INFO, searchProviders.size() + " search providers available");
    }

}

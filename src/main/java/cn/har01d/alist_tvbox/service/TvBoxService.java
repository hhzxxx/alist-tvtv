package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.annotation.CacheCheck;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.model.*;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.*;

@Slf4j
@Service
public class TvBoxService {

    @Autowired
    private IRedisService redisService;

    private final AListService aListService;
    private final IndexService indexService;
    private final MovieService movieService;
    private final SiteService siteService;
    private final AppProperties appProperties;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("名字⬆️", "name,asc"),
            new FilterValue("名字⬇️", "name,desc"),
            new FilterValue("时间⬆️", "time,asc"),
            new FilterValue("时间⬇️", "time,desc"),
            new FilterValue("大小⬆️", "size,asc"),
            new FilterValue("大小⬇️", "size,desc")
    );


    public TvBoxService(AListService aListService, IndexService indexService, MovieService movieService, SiteService siteService, AppProperties appProperties) {
        this.aListService = aListService;
        this.indexService = indexService;
        this.movieService = movieService;
        this.siteService = siteService;
        this.appProperties = appProperties;
    }

    public CategoryList getCategoryList() {
        CategoryList result = new CategoryList();

        for (Site site : siteService.list()) {
            Category category = new Category();
            category.setType_id(site.getId() + "$/");
            category.setType_name(site.getName());
            result.getList().add(category);
            result.getFilters().put(category.getType_id(), new Filter("sort", "排序", filters));
        }

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("category: {}", result);
        return result;
    }

    public MovieList search(String keyword) {
        MovieList result = new MovieList();
        List<Future<List<MovieDetail>>> futures = new ArrayList<>();
        for (Site site : siteService.list()) {
            if (site.isSearchable()) {
                if (StringUtils.hasText(site.getIndexFile())) {
                    futures.add(executorService.submit(() -> searchByFile(site, keyword)));
                } else {
                    futures.add(executorService.submit(() -> searchByApi(site, keyword)));
                }
            }
        }

        List<MovieDetail> list = new ArrayList<>();
        for (Future<List<MovieDetail>> future : futures) {
            try {
                list.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                log.warn("", e);
            }
        }

        log.info("search \"{}\" result: {}", keyword, list.size());
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    public static List<String> readAllLines = new ArrayList<>();

    private List<MovieDetail> searchByFile(Site site, String keyword) throws IOException {
        String indexFile = site.getIndexFile();
        if (indexFile.startsWith("http://") || indexFile.startsWith("https://")) {
            indexFile = indexService.downloadIndexFile(site);
        }

        log.info("search \"{}\" from site {}:{}, index file: {}", keyword, site.getId(), site.getName(), indexFile);
        Set<String> keywords = Arrays.stream(keyword.split("\\s+")).collect(Collectors.toSet());
        if(readAllLines.isEmpty()){
            readAllLines = Files.readAllLines(Paths.get(indexFile))
                    .stream()
                    .filter(path -> !path.contains("./电子书/"))
                    .filter(path -> !path.contains("./资料/"))
                    .collect(Collectors.toList());
        }
        Set<String> lines = readAllLines
                .stream()
                .filter(path -> keywords.stream().allMatch(path::contains))
                .sorted((o1, o2) -> {
                    if(keywords.stream().anyMatch(o1::equals)){
                        return 1;
                    }else {
                        return -1;
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MovieDetail> list = new ArrayList<>();
        for (String line : lines) {
            boolean isMediaFile = isMediaFile(line);
            if (isMediaFile && lines.contains(getParent(line))) {
                continue;
            }

            if(redisService.ignores.stream().anyMatch(line::contains)){
                continue;
            }
            String path = fixPath("/" + line + (isMediaFile ? "" : PLAYLIST));
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + path);
            movieDetail.setVod_name(site.getName() + ":" + line);
            String[] names = line.split("/");
            try {
                if(!names[names.length-1].contains(keyword)){
                    continue;
                }
                movieDetail.setVod_name("ya_"+names[names.length-1]);
            }catch (Exception ignore){}
            movieDetail.setVod_tag(isMediaFile ? FILE : FOLDER);
            list.add(movieDetail);
        }
        list.sort(Comparator.comparing(MovieDetail::getVod_id));

        log.debug("search \"{}\" from site {}:{}, result: {}", keyword, site.getId(), site.getName(), list.size());
        return list;
    }

    private List<MovieDetail> searchByApi(Site site, String keyword) {
        log.info("search \"{}\" from site {}:{}", keyword, site.getId(), site.getName());
        List<SearchResult> res = aListService.search(site, keyword,1);
        return  res
                .stream()
                .filter(a -> {
                    if(a.getParent().contains("book")){
                        return a.getType() == 1 && !isMediaFile(a.getName());
                    }
                    return true;
                })
                .map(e -> {
                    boolean isMediaFile = isMediaFile(e.getName());
                    String path = fixPath(e.getParent() + "/" + e.getName() + (isMediaFile ? "" : PLAYLIST));
                    MovieDetail movieDetail = new MovieDetail();
                    movieDetail.setVod_id(site.getId() + "$" + path);
                    movieDetail.setVod_name(e.getName());
                    movieDetail.setVod_tag(isMediaFile ? FILE : FOLDER);
                    return movieDetail;
                })
                .collect(Collectors.toList());
    }

    private boolean isMediaFile(String path) {
        String name = path;
        int index = path.lastIndexOf('/');
        if (index > -1) {
            name = path.substring(index + 1);
        }
        return isMediaFormat(name);
    }

    private Site getSite(String tid) {
        int index = tid.indexOf('$');
        String id = tid.substring(0, index);
        try {
            Integer siteId = Integer.parseInt(id);
            return siteService.getById(siteId);
        } catch (NumberFormatException e) {
            // ignore
        }

        return siteService.getByName(id);
    }

    public MovieList getMovieList(String tid, String sort, int page) {
        int index = tid.indexOf('$');
        Site site = getSite(tid);
        String path = tid.substring(index + 1);
        List<MovieDetail> folders = new ArrayList<>();
        List<MovieDetail> files = new ArrayList<>();
        List<MovieDetail> playlists = new ArrayList<>();
        MovieList result = new MovieList();

        int size = appProperties.getPageSize();
        FsResponse fsResponse = aListService.listFiles(site, path, page, size);
        int total = fsResponse.getTotal();

        for (FsInfo fsInfo : fsResponse.getFiles()) {
            if (fsInfo.getType() != 1 && fsInfo.getName().equals(PLAYLIST_TXT)) {
                playlists = generatePlaylistFromFile(site, path + "/" + PLAYLIST_TXT);
                total--;
                continue;
            }
            if (fsInfo.getType() != 1 && !isMediaFormat(fsInfo.getName())) {
                total--;
                continue;
            }

            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + fixPath(path + "/" + fsInfo.getName()));
            movieDetail.setVod_name(fsInfo.getName());
            movieDetail.setVod_tag(fsInfo.getType() == 1 ? FOLDER : FILE);
            movieDetail.setVod_pic(getCover(fsInfo.getThumb(), fsInfo.getType()));
            movieDetail.setVod_remarks(fileSize(fsInfo.getSize()) + (fsInfo.getType() == 1 ? "文件夹" : ""));
            movieDetail.setVod_time(fsInfo.getModified());
            movieDetail.setSize(fsInfo.getSize());
            if (fsInfo.getType() == 1) {
                folders.add(movieDetail);
            } else {
                files.add(movieDetail);
            }
        }

        sortFiles(sort, folders, files);

        result.getList().addAll(folders);

        if (page == 1 && files.size() > 1 && playlists.isEmpty()) {
            playlists = generatePlaylist(site.getId() + "$" + fixPath(path + PLAYLIST), total - folders.size(), files);
        }

        result.getList().addAll(playlists);
        result.getList().addAll(files);

        result.setPage(page);
        result.setTotal(total);
        result.setLimit(size);
        result.setPagecount((total + size - 1) / size);
        log.debug("list: {}", result);
        return result;
    }

    private void sortFiles(String sort, List<MovieDetail> folders, List<MovieDetail> files) {
        if (sort == null) {
            sort = "name,asc";
        }
        Comparator<MovieDetail> comparator;
        switch (sort) {
            case "name,asc":
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()));
                break;
            case "time,asc":
                comparator = Comparator.comparing(MovieDetail::getVod_time);
                break;
            case "size,asc":
                comparator = Comparator.comparing(MovieDetail::getSize);
                break;
            case "name,desc":
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()));
                comparator = comparator.reversed();
                break;
            case "time,desc":
                comparator = Comparator.comparing(MovieDetail::getVod_time);
                comparator = comparator.reversed();
                break;
            case "size,desc":
                comparator = Comparator.comparing(MovieDetail::getSize);
                comparator = comparator.reversed();
                break;
            default:
                return;
        }
        folders.sort(comparator);
        files.sort(comparator);
    }

    private List<MovieDetail> generatePlaylistFromFile(Site site, String path) {
        List<MovieDetail> list = new ArrayList<>();
        String content = aListService.readFileContent(site, path);
        if (content != null) {
            int count = 0;
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + path + "#" + 0);
            movieDetail.setVod_name("播放列表");
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);

            for (String line : content.split("[\r\n]")) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    if (text.startsWith("#cover")) {
                        movieDetail.setVod_pic(text.substring("#cover".length()).trim());
                    }
                    continue;
                }
                if (text.contains(",#genre#")) {
                    if (count > 0) {
                        movieDetail.setVod_remarks("共" + count + "集");
                        list.add(movieDetail);
                    }
                    count = 0;
                    String[] parts = text.split(",");
                    movieDetail = new MovieDetail();
                    movieDetail.setVod_id(site.getId() + "$" + path + "#" + list.size());
                    movieDetail.setVod_name(parts[0]);
                    movieDetail.setVod_tag(FILE);
                    movieDetail.setVod_pic(parts.length == 3 ? parts[2].trim() : LIST_PIC);
                } else {
                    count++;
                }
            }

            if (count > 0) {
                movieDetail.setVod_remarks("共" + count + "集");
                list.add(movieDetail);
            }
        }

        return list;
    }

    private List<MovieDetail> generatePlaylist(String path, int total, List<MovieDetail> files) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(path);
        movieDetail.setVod_name("播放列表");
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        if (total < appProperties.getPageSize()) {
            movieDetail.setVod_remarks("共" + files.size() + "集");
        }

        List<MovieDetail> list = new ArrayList<>();
        list.add(movieDetail);

        return list;
    }

    public String getPlayUrl(Integer siteId, String path,ServletUriComponentsBuilder builder) {
        Site site = siteService.getById(siteId);
        if(path.contains("/有声书/有声小说") && builder.build().getHost().contains("hhzhome")){
            site = siteService.getById(2);
        }
        log.info("get play url - site {}:{}  path: {}", site.getId(), site.getName(), path);
        FsDetail fsDetail = aListService.getFile(site, path);
        return fixHttp(fsDetail.getRaw_url());
    }

    @CacheCheck(exTime = 60*60*2)
    public MovieList getDetail(@CacheCheck String tid,@CacheCheck ServletUriComponentsBuilder builder) {
        int index = tid.indexOf('$');
        Site site = getSite(tid);
        String path = tid.substring(index + 1);
        if (path.contains(PLAYLIST) || path.contains(PLAYLIST_TXT)) {
            MovieList movieList = getPlaylist(site, path,builder);
            List<MovieDetail> list = movieList.getList();
            MovieDetail movieDetail = list.get(0);
            for (int i = 1; i < list.size(); i++) {
                MovieDetail d = list.get(i);
                if(!org.apache.commons.lang3.StringUtils.isBlank(d.getVod_play_url())){
                    if(org.apache.commons.lang3.StringUtils.isBlank(movieDetail.getVod_play_url())){
                        movieDetail.setVod_play_from(d.getVod_play_from());
                        movieDetail.setVod_play_url(d.getVod_play_url());
                    }else {
                        movieDetail.setVod_play_from(movieDetail.getVod_play_from()+"$$$"+d.getVod_play_from());
                        movieDetail.setVod_play_url(movieDetail.getVod_play_url()+"$$$"+d.getVod_play_url());
                    }
                }
            }
            list = new ArrayList<>();
            list.add(movieDetail);
            movieList.setList(list);
            if(org.apache.commons.lang3.StringUtils.isBlank(movieDetail.getVod_play_url())){
                return null;
            }
            return movieList;
        }

        FsDetail fsDetail = aListService.getFile(site, path);
        MovieList result = new MovieList();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(tid);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_tag(fsDetail.getType() == 1 ? FOLDER : FILE);
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_pic(getCover(fsDetail.getThumb(), fsDetail.getType()));
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_play_url(fsDetail.getName() + "$" + fixHttp(fsDetail.getRaw_url()));
        movieDetail.setVod_content(tid);
        movieService.readMetaData(movieDetail, site, path);
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private String buildPlayUrl(Site site, String path,ServletUriComponentsBuilder builder) {
        ServletUriComponentsBuilder builder1 = builder.cloneBuilder();
        if(builder1.build().getPort() == 9443 || builder1.build().getPort() == 443
          || builder1.build().getPort() == 32443){
            builder1.scheme("https");
        }
        builder1.replacePath("/alist/play");
        builder1.queryParam("site", String.valueOf(site.getId()));
        builder1.queryParam("path", encodeUrl(path));
        return builder1.build().toUriString();
    }


    public MovieList getPlaylist(Site site, String path,ServletUriComponentsBuilder builder) {
        log.info("load playlist {}:{} {}", site.getId(), site.getName(), path);
        if (!path.contains(PLAYLIST)) {
            return readPlaylistFromFile(site, path,builder);
        }
        String newPath = getParent(path);
        FsDetail fsDetail = aListService.getFile(site, newPath);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site.getId() + "$" + path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(fsDetail.getName());
        movieDetail.setVod_content(site.getName() + ":" + newPath);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        FsResponse fsResponse = aListService.listFiles(site, newPath, 1, 0);
        List<FsInfo> files = fsResponse.getFiles().stream()
                .filter(e -> isMediaFormat(e.getName()))
                .collect(Collectors.toList());
        List<FsInfo> dirs = fsResponse.getFiles().stream()
                .filter(e -> e.getType() == 1)
                .collect(Collectors.toList());

        if (appProperties.isSort()) {
            files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
        }

        List<String> list = new ArrayList<>();
        for (FsInfo fsInfo : files) {
            list.add(getName(fsInfo.getName()) + "$" + buildPlayUrl(site, newPath + "/" + fsInfo.getName(),builder));
        }

        movieDetail.setVod_play_url(String.join("#", list));
        movieService.readMetaData(movieDetail, site, newPath);

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        dirs.forEach(d -> {
            MovieList list1 = getPlaylist(site,path.replace("/~playlist","")+"/"+d.getName()+"/~playlist",builder);
            result.getList().addAll(list1.getList());
        });

        result.setLimit(result.getList().size());
        result.setTotal(result.getList().size());
        log.debug("playlist: {}", result);
        return result;
    }

    private MovieList readPlaylistFromFile(Site site, String path,ServletUriComponentsBuilder builder) {
        List<String> files = new ArrayList<>();
        int id = getPlaylistId(path);

        String newPath = getParent(path);
        String pname = "";
        FsDetail fsDetail = aListService.getFile(site, newPath);
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site.getId() + "$" + path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        String content = aListService.readFileContent(site, path);
        if (content != null) {
            int count = 0;
            for (String line : content.split("[\r\n]")) {
                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("#")) {
                    readMetadata(movieDetail, text);
                    continue;
                }
                if (text.contains(",#genre#")) {
                    if (files.size() > 0) {
                        count++;
                    }
                    if (count > id) {
                        break;
                    }
                    pname = text.split(",")[0];
                    files = new ArrayList<>();
                } else {
                    files.add(text);
                }
            }
        }

        List<String> list = new ArrayList<>();
        for (String line : files) {
            try {
                String name = line.split(",")[0];
                String file = line.split(",")[1];
                list.add(name + "$" + buildPlayUrl(site, newPath + "/" + file,builder));
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        movieDetail.setVod_play_url(String.join("#", list));
        movieDetail.setVod_name(movieDetail.getVod_name() + " " + pname);

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        log.debug("playlist: {}", result);
        return result;
    }

    private void readMetadata(MovieDetail movieDetail, String text) {
        if (text.startsWith("#name")) {
            String name = text.substring("#name".length()).trim();
            if (!name.isEmpty()) {
                movieDetail.setVod_name(name);
            }
        } else if (text.startsWith("#type")) {
            movieDetail.setType_name(text.substring("#type".length()).trim());
        } else if (text.startsWith("#actor")) {
            movieDetail.setVod_actor(text.substring("#actor".length()).trim());
        } else if (text.startsWith("#director")) {
            movieDetail.setVod_director(text.substring("#director".length()).trim());
        } else if (text.startsWith("#content")) {
            movieDetail.setVod_content(text.substring("#content".length()).trim());
        } else if (text.startsWith("#lang")) {
            movieDetail.setVod_lang(text.substring("#lang".length()).trim());
        } else if (text.startsWith("#area")) {
            movieDetail.setVod_area(text.substring("#area".length()).trim());
        } else if (text.startsWith("#year")) {
            movieDetail.setVod_year(text.substring("#year".length()).trim());
        }
    }

    private int getPlaylistId(String path) {
        try {
            int index = path.lastIndexOf('/');
            if (index > 0) {
                String[] parts = path.substring(index + 1).split("#");
                if (parts.length >= 2) {
                    return Integer.parseInt(parts[parts.length - 1]);
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return -1;
    }

    private static String getCover(String thumb, int type) {
        String pic = thumb;
        if (pic.isEmpty() && type == 1) {
            pic = FOLDER_PIC;
        }
        return pic;
    }

    private String fileSize(long size) {
        double sz = size;
        String filesize;
        if (sz > 1024 * 1024 * 1024 * 1024.0) {
            sz /= (1024 * 1024 * 1024 * 1024.0);
            filesize = "TB";
        } else if (sz > 1024 * 1024 * 1024.0) {
            sz /= (1024 * 1024 * 1024.0);
            filesize = "GB";
        } else if (sz > 1024 * 1024.0) {
            sz /= (1024 * 1024.0);
            filesize = "MB";
        } else {
            sz /= 1024.0;
            filesize = "KB";
        }
        String remark = "";
        if (size > 0) {
            remark = String.format("%.2f%s", sz, filesize);
        }
        return remark;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }

    private String getParent(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(0, index);
        }
        return path;
    }

    private String getName(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(0, index);
        }
        return name;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    private String fixHttp(String url) {
        if (url.startsWith("//")) {
            return "http:" + url;
        }
        return url;
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return url;
        }
    }
}

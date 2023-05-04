package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.model.*;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AListService {
    private static final Pattern VERSION = Pattern.compile("\"version\":\"v\\d+\\.\\d+\\.\\d+\"");

    private final RestTemplate restTemplate;
    private final SiteService siteService;

    @Autowired
    private IRedisService redisService;


    public AListService(RestTemplateBuilder builder, SiteService siteService) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
        this.siteService = siteService;
    }

    public List<SearchResult> search(Site site, String keyword,int count) {
        if(count > 2){
            return new ArrayList<>();
        }
        String searchWord = keyword;
        if(count > 1){
            searchWord = searchWord.substring(0,Math.max((keyword.length())/count,1));
        }
        String url = site.getUrl() + "/api/fs/search?keyword=" + searchWord;
        SearchRequest request = new SearchRequest();
        request.setPassword(site.getPassword());
        request.setKeywords(searchWord);
        SearchListResponse response = restTemplate.postForObject(url, request, SearchListResponse.class);
        logError(response);
        log.debug("search \"{}\" from site {}:{} result: {}", searchWord, site.getId(), site.getName(), response.getData().getContent().size());
        List<SearchResult> list = response.getData().getContent().stream().filter(a -> a.getType() != 5).collect(Collectors.toList());
        List<String> hides = new ArrayList<>();
        list.forEach(r -> {
            if(r.getType() == 1 && (r.getParent().contains("电视剧") || r.getParent().contains("动漫"))){
                hides.add(r.getParent()+"/"+r.getName());
            }
        });
        if(list.isEmpty()){
            return search(site,keyword,count + 1);
        }else {
            list = list.stream().filter(a -> !hides.contains(a.getParent()) || a.getType() == 1)
                    .filter(a -> a.getType()!=4).collect(Collectors.toList());
            return list;
        }
    }

    public List<FileItem> browse(int id, String path) {
        List<FileItem> list = new ArrayList<>();
        if (StringUtils.isEmpty(path)) {
            list.add(new FileItem("/", "/", 1));
            return list;
        }

        Site site = siteService.getById(id);
        FsResponse response = listFiles(site, path, 1, 1000);
        for (FsInfo fsInfo : response.getFiles()) {
            FileItem item = new FileItem(fsInfo.getName(), fixPath(path + "/" + fsInfo.getName()), fsInfo.getType());
            list.add(item);
        }
        return list;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    public FsResponse listFiles(Site site, String path, int page, int size) {
        int version = getVersion(site);
        String url = site.getUrl() + (version == 2 ? "/api/public/path" : "/api/fs/list");
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path.replace("/mv","").replace("/xioya","")
                .replace("/.",""));
        request.setPage(page);
        request.setSize(size);
        log.debug("call api: {}", url);
        FsListResponse response = restTemplate.postForObject(url, request, FsListResponse.class);
        logError(response);
        log.debug("list files: {} {}", path, response.getData());
        return getFiles(version, response.getData());
    }

    private FsResponse getFiles(int version, FsResponse response) {
        if (version == 2) {
            for (FsInfo fsInfo : response.getFiles()) {
                fsInfo.setThumb(fsInfo.getThumbnail());
            }
        } else if (response != null && response.getContent() != null) {
            response.setFiles(response.getContent());
        }
        return response;
    }

    public String readFileContent(Site site, String path) {
        // TODO: fix it
        String url = site.getUrl() + "/p" + path;
        return restTemplate.getForObject(url, String.class);
    }

    public FsDetail getFile(Site site, String path) {
        int version = getVersion(site);
        if (version == 2) {
            return getFileV2(site, path);
        } else {
            return getFileV3(site, path);
        }
    }

    private FsDetail getFileV3(Site site, String path) {
        String url = site.getUrl() + "/api/fs/get";
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path.replace("/mv","").replace("/xioya","")
                .replace("/.",""));
        log.debug("call api: {}", url);
        try {
            FsDetailResponse response = restTemplate.postForObject(url, request, FsDetailResponse.class);
            logError(response);
            log.debug("get file: {} {}", path, response.getData());
            return response.getData();
        }catch (Exception e){
            if(path.contains("/./") && !redisService.ignores.contains(path.replace("/.",""))){
                redisService.ignores.add(path.replace("/.",""));
            }
            return null;
        }
    }

    private FsDetail getFileV2(Site site, String path) {
        String url = site.getUrl() + "/api/public/path";
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path);
        log.debug("call api: {}", url);
        FsListResponseV2 response = restTemplate.postForObject(url, request, FsListResponseV2.class);
        logError(response);
        FsInfoV2 fsInfo = Optional.ofNullable(response)
                .map(Response::getData)
                .map(FsResponseV2::getFiles)
                .filter(l -> !l.isEmpty())
                .map(l -> l.get(0))
                .orElse(null);
        if (fsInfo != null) {
            FsDetail fsDetail = new FsDetail();
            fsDetail.setName(fsInfo.getName());
            fsDetail.setThumb(fsInfo.getThumbnail());
            fsDetail.setSize(fsInfo.getSize());
            fsDetail.setRaw_url(fsInfo.getUrl());
            fsDetail.setType(fsInfo.getType());
            fsDetail.setProvider(fsInfo.getDriver());
            log.debug("get file: {} {}", path, fsDetail);
            return fsDetail;
        }
        return null;
    }

    private Integer getVersion(Site site) {
        if (site.getVersion() != null) {
            return site.getVersion();
        }

        String url = site.getUrl() + "/api/public/settings";
        log.debug("call api: {}", url);
        String text = restTemplate.getForObject(url, String.class);
        int version;
        if (text != null && VERSION.matcher(text).find()) {
            version = 3;
        } else {
            version = 2;
        }
        log.info("site {}:{} version: {}", site.getId(), site.getName(), version);
        site.setVersion(version);
        siteService.save(site);

        return version;
    }

    private void logError(Response<?> response) {
        if (response != null && response.getCode() != 200) {
            log.warn("error {} {}", response.getCode(), response.getMessage());
        }
    }
}

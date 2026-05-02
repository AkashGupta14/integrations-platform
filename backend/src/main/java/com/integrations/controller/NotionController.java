package com.integrations.controller;

import com.integrations.model.IntegrationItem;
import com.integrations.service.NotionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/integrations/notion")
public class NotionController {

    private final NotionService notionService;

    public NotionController(NotionService notionService) {
        this.notionService = notionService;
    }

    @PostMapping("/authorize")
    public String authorize(@RequestParam("user_id") String userId,
                            @RequestParam("org_id") String orgId) throws Exception {
        return notionService.authorize(userId, orgId);
    }

    @GetMapping("/oauth2callback")
    public ResponseEntity<String> oauth2callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error)
            throws Exception {
        String html = notionService.oauth2callback(code, state, error);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping("/credentials")
    public Object getCredentials(@RequestParam("user_id") String userId,
                                 @RequestParam("org_id") String orgId) throws Exception {
        return notionService.getCredentials(userId, orgId);
    }

    @PostMapping("/load")
    public List<IntegrationItem> load(@RequestParam("credentials") String credentials)
            throws Exception {
        return notionService.getItems(credentials);
    }
}

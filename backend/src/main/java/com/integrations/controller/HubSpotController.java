package com.integrations.controller;

import com.integrations.model.IntegrationItem;
import com.integrations.service.HubSpotService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/integrations/hubspot")
public class HubSpotController {

    private final HubSpotService hubSpotService;

    public HubSpotController(HubSpotService hubSpotService) {
        this.hubSpotService = hubSpotService;
    }

    @PostMapping("/authorize")
    public String authorize(@RequestParam("user_id") String userId,
                            @RequestParam("org_id") String orgId) throws Exception {
        return hubSpotService.authorize(userId, orgId);
    }

    @GetMapping("/oauth2callback")
    public ResponseEntity<String> oauth2callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription)
            throws Exception {
        String html = hubSpotService.oauth2callback(code, state, error, errorDescription);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping("/credentials")
    public Object getCredentials(@RequestParam("user_id") String userId,
                                 @RequestParam("org_id") String orgId) throws Exception {
        return hubSpotService.getCredentials(userId, orgId);
    }

    @PostMapping("/load")
    public List<IntegrationItem> load(@RequestParam("credentials") String credentials)
            throws Exception {
        return hubSpotService.getItems(credentials);
    }
}

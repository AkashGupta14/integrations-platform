package com.integrations.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationItem {

    private String id;
    private String type;
    @Builder.Default
    private boolean directory = false;
    private String parentPathOrName;
    private String parentId;
    private String name;
    private String creationTime;
    private String lastModifiedTime;
    private String url;
    private List<String> children;
    private String mimeType;
    private String delta;
    private String driveId;
    @Builder.Default
    private Boolean visibility = true;
}

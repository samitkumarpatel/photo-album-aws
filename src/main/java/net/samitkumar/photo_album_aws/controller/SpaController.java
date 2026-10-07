package net.samitkumar.photo_album_aws.controller;

import net.samitkumar.photo_album_aws.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// Serves the bundled SPA; contexts serving the frontend separately can disable this with spring.application.spa.enabled=false.
@Controller
@ConditionalOnProperty(prefix = "spring.application.spa", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpaController {
    @GetMapping({"/", "/photos", "/albums", "/albums/{albumId}", "/share/{token}"})
    public String app() { return "forward:/index.html"; }
}

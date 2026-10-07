package net.samitkumar.photo_album_aws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// Serves the bundled SPA for local runs and the fat jar. On AWS CloudFront serves it, so the lambda profile turns this off.
@Controller
@ConditionalOnProperty(prefix = "spring.application.spa", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpaController {
    @GetMapping({"/", "/photos", "/albums", "/albums/{albumId}", "/share/{token}"})
    public String app() { return "forward:/index.html"; }
}

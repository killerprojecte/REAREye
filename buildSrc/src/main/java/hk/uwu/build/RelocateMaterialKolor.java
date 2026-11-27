package hk.uwu.build;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;

@CacheableTask
public abstract class RelocateMaterialKolor extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getArtifacts();
    @OutputFile public abstract RegularFileProperty getOutputJar();
    @TaskAction public void relocate() throws IOException {
        MaterialKolorNamespace.materialJar(getArtifacts().getSingleFile(), getOutputJar().get().getAsFile());
    }
}

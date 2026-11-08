package hk.uwu.build;

import java.io.IOException;
import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;

@CacheableTransform
public abstract class RelocateMochiArtifact implements TransformAction<TransformParameters.None> {
    @InputArtifact @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract Provider<FileSystemLocation> getInputArtifact();
    @Override public void transform(TransformOutputs outputs) {
        var input = getInputArtifact().get().getAsFile();
        if (!input.getName().equals("mochi-compose-0.2.0.aar")) {
            outputs.file(input);
            return;
        }
        try { MaterialKolorNamespace.mochiArtifact(input, outputs.file(input.getName())); }
        catch (IOException e) { throw new RuntimeException("Cannot isolate Maven Mochi MaterialKolor references", e); }
    }
}

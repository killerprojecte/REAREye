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
        try {
            switch (input.getName()) {
                case "mochi-compose-0.2.0.aar" ->
                    MaterialKolorNamespace.mochiArtifact(input, outputs.file(input.getName()));
                case NothingFontCompatibility.ARTIFACT_NAME ->
                    NothingFontCompatibility.sanitizeArtifact(input, outputs.file(input.getName()));
                default -> outputs.file(input);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot prepare Maven Mochi compatibility artifact: " + input.getName(), e);
        }
    }
}

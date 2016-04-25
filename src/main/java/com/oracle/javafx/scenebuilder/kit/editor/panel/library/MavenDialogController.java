package com.oracle.javafx.scenebuilder.kit.editor.panel.library;

import com.oracle.javafx.scenebuilder.kit.editor.panel.library.maven.MavenArtifact;
import com.oracle.javafx.scenebuilder.app.DocumentWindowController;
import com.oracle.javafx.scenebuilder.app.preferences.PreferencesController;
import com.oracle.javafx.scenebuilder.app.preferences.PreferencesRecordArtifact;
import com.oracle.javafx.scenebuilder.kit.editor.EditorController;
import com.oracle.javafx.scenebuilder.kit.editor.i18n.I18N;
import com.oracle.javafx.scenebuilder.kit.editor.panel.library.maven.MavenRepositorySystem;
import com.oracle.javafx.scenebuilder.kit.editor.panel.library.maven.search.SearchService;
import com.oracle.javafx.scenebuilder.kit.editor.panel.util.AbstractFxmlWindowController;
import com.oracle.javafx.scenebuilder.kit.editor.panel.util.dialog.AbstractModalDialog.ButtonID;
import com.oracle.javafx.scenebuilder.kit.library.user.UserLibrary;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.version.Version;


/**
 * Controller for the JAR Maven dialog.
 */
public class MavenDialogController extends AbstractFxmlWindowController {

    @FXML
    private TextField groupIDTextfield;

    @FXML
    private TextField artifactIDTextfield;

    @FXML
    private ComboBox<Version> versionsCombo;
    
    @FXML
    private TextField searchTextfield;
    
    @FXML
    private Button searchButton;
    
    @FXML
    private ListView<String> resultsListView;

    @FXML
    private ProgressIndicator progress;
    
    @FXML
    private Button installButton;

    private final DocumentWindowController documentWindowController;

    private final UserLibrary userLibrary;
    
    private MavenRepositorySystem maven;
    private RemoteRepository remoteRepository;
    private Service<ObservableList<Version>> versionsService;
    private final SearchService searchService;
    private final Service<MavenArtifact> installService;
    private final Window owner;
    
    private final ChangeListener<Version> comboBoxListener = (obs, ov, nv) -> {
        remoteRepository = maven.getRemoteRepository(nv);
    };
    
    private final ChangeListener<Boolean> serviceListener = (obs, ov, nv) -> {
        if (!nv) {
            callVersionsService();
        }
    };
    
    private final boolean latest;
    
    public MavenDialogController(EditorController editorController, DocumentWindowController documentWindowController, 
            Window owner, boolean latest) {
        super(MavenDialogController.class.getResource("MavenDialog.fxml"), I18N.getBundle(), owner); //NOI18N
        this.documentWindowController = documentWindowController;
        this.userLibrary = (UserLibrary) editorController.getLibrary();
        this.owner = owner;
        this.latest = latest;
        
        maven = new MavenRepositorySystem(latest);
        
        versionsService = new Service<ObservableList<Version>>() {
            @Override
            protected Task<ObservableList<Version>> createTask() {
                return new Task<ObservableList<Version>>() {
                    @Override
                    protected ObservableList<Version> call() throws Exception {
                        return FXCollections.observableArrayList(getVersions());
                    }
                };
            }
        };
        
        versionsService.stateProperty().addListener((obs, ov, nv) -> {
            if (nv.equals(Worker.State.SUCCEEDED)) {
                versionsCombo.getItems().setAll(versionsService.getValue()
                        .filtered(v -> !latest ||
                                (latest && !v.toString().toUpperCase(Locale.ROOT).contains("SNAPSHOT")))
                        .sorted((v1, v2) -> v2.compareTo(v1)));
                versionsCombo.setCellFactory(p -> new ListCell<Version>() {
                    @Override
                    protected void updateItem(Version item, boolean empty) {
                        super.updateItem(item, empty); 
                        if (item != null && !empty) {
                            final RemoteRepository remote = maven.getRemoteRepository(item);
                            setText(item + " [" + remote.getId() + "]");
                        } else {
                            setText(null);
                        }
                    }
                    
                });
                versionsCombo.getSelectionModel().selectedItemProperty().addListener(comboBoxListener);
                if (!latest) {
                    versionsCombo.setDisable(false);
                } else if (!versionsCombo.getItems().isEmpty()) {
                    versionsCombo.getSelectionModel().select(0);
                }
            } else if (nv.equals(Worker.State.CANCELLED) || nv.equals(Worker.State.FAILED)) {
                if (!latest) {
                    versionsCombo.setDisable(false);
                }
            }
        });
        
        searchService = new SearchService();
        searchService.getResult().addListener((ListChangeListener.Change<? extends String> c) -> {
            while (c.next()) {
                resultsListView.getItems().setAll(searchService.getResult()
                        .stream()
                        .sorted()
                        .collect(Collectors.toList()));
            }
        });
        
        installService = new Service<MavenArtifact>() {
            @Override
            protected Task<MavenArtifact> createTask() {
                return new Task<MavenArtifact>() {
                    @Override
                    protected MavenArtifact call() throws Exception {
                        return resolveArtifacts();
                    }
                };
            }
        };
        
        installService.stateProperty().addListener((obs, ov, nv) -> {
            if (nv.equals(Worker.State.SUCCEEDED)) {
                final MavenArtifact mavenArtifact = installService.getValue();
                final PreferencesController pc = PreferencesController.getSingleton();
    
                List<File> files = new ArrayList<>();
                files.add(new File(mavenArtifact.getPath()));
                if (!mavenArtifact.getDependencies().isEmpty()) {
                    files.addAll(Stream
                            .of(mavenArtifact.getDependencies().split(":"))
                            .map(File::new)
                            .collect(Collectors.toList()));
                }
                
                final ImportWindowController iwc
                        = new ImportWindowController(
                            new LibraryPanelController(editorController), 
                            files, installButton.getScene().getWindow(), false,
                            pc.getMavenPreferences().getArtifactsFilter());
                iwc.setToolStylesheet(editorController.getToolStylesheet());
                ButtonID userChoice = iwc.showAndWait();
                if (userChoice == ButtonID.OK) {
                    mavenArtifact.setFilter(iwc.getNewExcludedItems());
                    updatePreferences(mavenArtifact);
                    logInfoMessage("log.user.maven.installed", getArtifactCoordinates());
                }
                this.onCloseRequest(null);
            } else if (nv.equals(Worker.State.CANCELLED) || nv.equals(Worker.State.FAILED)) {
                logInfoMessage("log.user.maven.failed", getArtifactCoordinates());
            }
        });
        
    }
    
    @Override
    protected void controllerDidCreateStage() {
        if (this.owner == null) {
            // Dialog will be appliation modal
            getStage().initModality(Modality.APPLICATION_MODAL);
        } else {
            // Dialog will be window modal
            getStage().initOwner(this.owner);
            getStage().initModality(Modality.WINDOW_MODAL);
        }
    }

    @Override
    public void onCloseRequest(WindowEvent event) {
        cancel();
    }

    @Override
    public void openWindow() {
        super.openWindow();
        super.getStage().setTitle(I18N.getString("maven.dialog.title"));
        installButton.disableProperty().bind(groupIDTextfield.textProperty().isEmpty().or(
                      artifactIDTextfield.textProperty().isEmpty().or(
                      versionsCombo.getSelectionModel().selectedIndexProperty().lessThan(0))));
        installButton.setTooltip(new Tooltip(I18N.getString("maven.dialog.install.tooltip")));
        
        versionsCombo.setDisable(true);
        
        groupIDTextfield.setDisable(latest);
        artifactIDTextfield.setDisable(latest);
        if (!latest) {
            groupIDTextfield.focusedProperty().addListener(serviceListener);
            groupIDTextfield.setOnAction(e -> callVersionsService());
            artifactIDTextfield.focusedProperty().addListener(serviceListener);
            artifactIDTextfield.setOnAction(e -> callVersionsService());
        }
        
        searchButton.disableProperty().bind(searchTextfield.textProperty().isEmpty());
        searchTextfield.setOnAction(e -> searchButton.fire());
        searchButton.setOnAction(e -> {
            if (progress.isVisible()) {
                searchService.cancelSearch();
            } else {
                searchService.setQuery(searchTextfield.getText());
                searchService.restart();
            }
        });
        
        resultsListView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null && !nv.isEmpty() && nv.contains(":")) {
                resolveVersions(nv.split(":")[0], nv.split(":")[1]);
            }
        });
        
        searchButton.textProperty().bind(Bindings.when(progress.visibleProperty())
                .then(I18N.getString("maven.dialog.button.cancel"))
                .otherwise(I18N.getString("maven.dialog.button.search")));
        searchButton.tooltipProperty().bind(Bindings.when(progress.visibleProperty())
                .then(new Tooltip(I18N.getString("maven.dialog.button.cancel.tooltip")))
                .otherwise(new Tooltip(I18N.getString("maven.dialog.button.search.tooltip"))));
        
        progress.visibleProperty().bind(versionsService.runningProperty()
                .or(installService.runningProperty()
                        .or(searchService.searchingProperty())));
    }

    @FXML
    private void installJar() {
        installService.restart();
    }
    
    @FXML
    private void cancel() {
        groupIDTextfield.focusedProperty().removeListener(serviceListener);
        artifactIDTextfield.focusedProperty().removeListener(serviceListener);
        installButton.disableProperty().unbind();
        progress.visibleProperty().unbind();
        
        groupIDTextfield.clear();
        artifactIDTextfield.clear();
        versionsCombo.getSelectionModel().selectedItemProperty().removeListener(comboBoxListener);
        versionsCombo.getItems().clear();
        versionsCombo.setDisable(true);
        
        searchTextfield.clear();
        resultsListView.getItems().clear();
        
        closeWindow();
    }
    
    public void resolveVersions(String groupId, String artifactId) {
        groupIDTextfield.setText(groupId);
        artifactIDTextfield.setText(artifactId);
        callVersionsService();
    }
    
    private void callVersionsService() {
        if (groupIDTextfield.getText().isEmpty() || artifactIDTextfield.getText().isEmpty()) {
            return;
        }
        versionsCombo.getSelectionModel().selectedItemProperty().removeListener(comboBoxListener);
        versionsCombo.getItems().clear();
        versionsCombo.setDisable(true);
        versionsService.restart();
    }
    
    private List<Version> getVersions() {
        Artifact artifact = new DefaultArtifact(groupIDTextfield.getText() + ":" + 
                artifactIDTextfield.getText() + ":[0,)");

        return maven.findVersions(artifact);
    }
    
    private MavenArtifact resolveArtifacts() {
        if (remoteRepository == null) {
            return null;
        }
        
        String[] coordinates = getArtifactCoordinates().split(":");
        Artifact jarArtifact = new DefaultArtifact(coordinates[0], 
                coordinates[1], "", "jar", coordinates[2]);

        Artifact javadocArtifact = new DefaultArtifact(coordinates[0], 
                coordinates[1], "javadoc", "jar", coordinates[2]);

        Artifact pomArtifact = new DefaultArtifact(coordinates[0], 
                coordinates[1], "", "pom", coordinates[2]);

        MavenArtifact mavenArtifact = new MavenArtifact(getArtifactCoordinates());
        mavenArtifact.setPath(maven.resolveArtifacts(remoteRepository, jarArtifact, javadocArtifact, pomArtifact));
        mavenArtifact.setDependencies(maven.resolveDependencies(remoteRepository, jarArtifact));
        
        return mavenArtifact;
    }

    private void logInfoMessage(String key, Object... args) {
        documentWindowController.getEditorController().getMessageLog().logInfoMessage(key, I18N.getBundle(), args);
    }
    
    private String getArtifactCoordinates() {
        return groupIDTextfield.getText() + ":" + artifactIDTextfield.getText() + ":" + 
                versionsCombo.getSelectionModel().getSelectedItem().toString();
    }
    
    private void updatePreferences(MavenArtifact mavenArtifact) {
        if (mavenArtifact == null) {
            return;
        }
        
        userLibrary.stopWatching();
        
        // Update record artifact
        final PreferencesRecordArtifact recordArtifact = PreferencesController.getSingleton().
                getRecordArtifact(mavenArtifact);
        recordArtifact.writeToJavaPreferences();

        userLibrary.startWatching();
    }
    
}
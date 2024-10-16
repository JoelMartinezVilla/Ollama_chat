package com.project;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.event.ActionEvent;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.Initializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.json.JSONObject;

public class Controller implements Initializable {

    @FXML
    private Button buttonCallStream, buttonCallImage, buttonBreak;

    @FXML
    private TextField textField;

    @FXML
    private VBox responsesContainer;

    private Text responseText = new Text();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private CompletableFuture<HttpResponse<InputStream>> streamRequest;
    private AtomicBoolean isCancelled = new AtomicBoolean(false);
    private InputStream currentInputStream;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> streamReadingTask;
    private boolean isFirst = false;
    private Label label;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setButtonsIdle();
        responsesContainer.getChildren().add(responseText);
    }

    @FXML
    private void callStream(ActionEvent event) {
        setButtonsRunning();
        isCancelled.set(false);
        String prompt = textField.getText();

        try {
            JSONObject jsonRequest = new JSONObject();
            jsonRequest.put("model", "llama3.2:1b");
            jsonRequest.put("prompt", prompt);
            jsonRequest.put("stream", true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(jsonRequest.toString()))
                    .build();

            isFirst = true;
            streamRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(response -> {
                        currentInputStream = response.body();

                        streamReadingTask = executorService.submit(() -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(currentInputStream))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (isCancelled.get()) {
                                        break;
                                    }
                                    JSONObject jsonResponse = new JSONObject(line);
                                    String responseTextContent = jsonResponse.getString("response");

                                    Platform.runLater(() -> {
                                        if (isFirst) {
                                            label = new Label(
                                                    "You:\n" + prompt + "\n\nHentAI:\n" + responseTextContent);
                                            label.setWrapText(true);
                                            isFirst = false;
                                            responsesContainer.getChildren().add(label);
                                        } else {
                                            label.setText(label.getText() + responseTextContent);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                handleException(e, "Error during streaming");
                            } finally {
                                cleanupResources();
                            }
                        });
                        return response;
                    })
                    .exceptionally(e -> {
                        handleException(e, "Error during request");
                        return null;
                    });
        } catch (Exception e) {
            handleException(e, "Error preparing request");
        }
    }

    @FXML
    private void actionLoad(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select an Image");
        fileChooser.getExtensionFilters()
                .addAll(new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg"));

        Stage stage = (Stage) responsesContainer.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            try {
                // Cargar y mostrar la imagen
                Image image = new Image(selectedFile.toURI().toString());
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(200);
                imageView.setPreserveRatio(true);
                Platform.runLater(() -> responsesContainer.getChildren().add(imageView));

                BufferedImage bufferedImage = ImageIO.read(selectedFile);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                ImageIO.write(bufferedImage, "png", outputStream);
                String base64Image = Base64.getEncoder().encodeToString(outputStream.toByteArray());

                // Crear la solicitud JSON
                JSONObject jsonRequest = new JSONObject();
                jsonRequest.put("model", "llava-phi3");
                jsonRequest.put("prompt", "Describeme esta foto en español:");
                jsonRequest.put("images", new String[] { base64Image });
                jsonRequest.put("stream", false);

                // Crear la solicitud HTTP
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:11434/api/generate"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(jsonRequest.toString()))
                        .build();

                // Enviar la solicitud y manejar la respuesta
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                        .thenAccept(response -> handleImageResponse(response.body())) // Utilizar thenAccept
                        .exceptionally(e -> {
                            handleException(e, "Error during image processing");
                            return null;
                        });
            } catch (Exception e) {
                handleException(e, "Error loading image");
            }
        }
    }

    private void handleImageResponse(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled.get())
                    break;
                JSONObject jsonResponse = new JSONObject(line);
                String responseTextContent = jsonResponse.getString("response");
    
                Platform.runLater(() -> {
                    // Crear un nuevo Label para cada respuesta de imagen
                    Label newLabel = new Label("HentAI:\n" + responseTextContent);
                    newLabel.setWrapText(true);
                    responsesContainer.getChildren().add(newLabel);
                });
            }
        } catch (Exception e) {
            handleException(e, "Error reading image response");
        } finally {
            cleanupResources();
        }
    }
    
    

    @FXML
    private void callBreak(ActionEvent event) {
        isCancelled.set(true);
        cancelStreamRequest();
        Platform.runLater(() -> {
            responseText.setText(responseText.getText() + "\nRequest cancelled.");
            setButtonsIdle();
        });
    }

    private void cancelStreamRequest() {
        if (streamRequest != null && !streamRequest.isDone()) {
            if (streamReadingTask != null) {
                streamReadingTask.cancel(true);
            }
            streamRequest.cancel(true);
        }
    }

    private void handleException(Throwable e, String message) {
        e.printStackTrace();
        Platform.runLater(() -> responseText.setText(message + ": " + e.getMessage()));
        setButtonsIdle();
    }

    private void cleanupResources() {
        try {
            if (currentInputStream != null) {
                currentInputStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Platform.runLater(this::setButtonsIdle);
    }

    private void setButtonsRunning() {
        buttonCallStream.setDisable(true);
        buttonCallImage.setDisable(true);
        buttonBreak.setDisable(false);
    }

    private void setButtonsIdle() {
        buttonCallStream.setDisable(false);
        buttonCallImage.setDisable(false);
        buttonBreak.setDisable(true);
        streamRequest = null;
    }
}

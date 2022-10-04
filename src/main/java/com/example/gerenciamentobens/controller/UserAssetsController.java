package com.example.gerenciamentobens.controller;

import com.amazonaws.services.s3.AmazonS3;
import com.example.gerenciamentobens.entity.assets.Asset;
import com.example.gerenciamentobens.entity.assets.AssetDTO;
import com.example.gerenciamentobens.entity.assets.AssetsRepository;
import com.example.gerenciamentobens.entity.user.User;
import com.example.gerenciamentobens.entity.user.UserRepository;
import com.example.gerenciamentobens.service.DynamoUtilsService;
import com.example.gerenciamentobens.service.S3UtilsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.data.domain.ExampleMatcher.GenericPropertyMatchers.contains;

@RestController
@RequestMapping("/users/assets")
public class UserAssetsController {

    private final AssetsRepository assetsRepository;
    private final UserRepository userRepository;
    private final S3UtilsService s3UtilsService;
    private final AmazonS3 s3Client;
    private final DynamoUtilsService dynamoUtilsService;

    @Autowired
    public UserAssetsController(AssetsRepository assetsRepository, UserRepository userRepository, S3UtilsService s3UtilsService, AmazonS3 s3Client, DynamoUtilsService dynamoUtilsService) {
        this.assetsRepository = assetsRepository;
        this.userRepository = userRepository;
        this.s3UtilsService = s3UtilsService;
        this.s3Client = s3Client;
        this.dynamoUtilsService = dynamoUtilsService;
    }

    @GetMapping("")
    public ResponseEntity<Iterable<Asset>> getAssets(@AuthenticationPrincipal UserDetails userDetails,
                                                     @RequestParam(required = false) String name,
                                                     @RequestParam(required = false) String location) {
        ExampleMatcher matcher = ExampleMatcher
                .matchingAll()
                .withMatcher("name", contains().ignoreCase())
                .withMatcher("location", contains().ignoreCase());
        Asset example = new Asset(null, null, name, null, location, null);

        return ResponseEntity.ok(assetsRepository.findAll(Example.of(example, matcher)));
    }

    @PostMapping("")
    public ResponseEntity<Asset> insertNewAsset(@AuthenticationPrincipal UserDetails userDetails,
                                                @ModelAttribute AssetDTO assetDTO) {
        assetDTO.validateFileFormat();
        User user = userRepository.findByUsername(userDetails.getUsername()).get();
        var asset = assetDTO.toModel(user, userDetails.getUsername() + "/" + assetDTO.getName());
        var createdAsset = assetsRepository.save(asset);


        s3UtilsService.createOrUpdateObject(s3Client, assetDTO.getFile(), createdAsset.getFileReference());

        return ResponseEntity.status(HttpStatus.CREATED).body(createdAsset);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Asset> updateAsset(@AuthenticationPrincipal UserDetails userDetails,
                                             @ModelAttribute AssetDTO assetDTO,
                                             @PathVariable Long id) {
        assetDTO.validateFileFormat();
        User user = userRepository.findByUsername(userDetails.getUsername()).get();
        Asset userAsset = assetsRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "O bem não foi encontrado"));

        String newFilePath = userDetails.getUsername() + "/" + assetDTO.getName();
        String oldFilePath = userAsset.getFileReference();

        var asset = assetDTO.toModel(user, newFilePath, id);
        var updatedAsset = assetsRepository.save(asset);

        s3UtilsService.deleteObjectIfExists(s3Client, oldFilePath);
        s3UtilsService.createOrUpdateObject(s3Client, assetDTO.getFile(), newFilePath);

        return ResponseEntity.status(HttpStatus.OK).body(updatedAsset);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Asset> deleteAsset(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        var userAsset = assetsRepository.findByIdAndUserUsername(id, userDetails.getUsername()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "O bem não foi encontrado"));

        s3UtilsService.deleteObjectIfExists(s3Client, userAsset.getFileReference());
        dynamoUtilsService.deleteAllValidationsFromAsset(userAsset.getId());
        assetsRepository.deleteById(id);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}")
    public ResponseEntity<String> getAssetPresignedUrl(@AuthenticationPrincipal UserDetails userDetails, @PathVariable("name") String fileName){
        String filePath = userDetails.getUsername() + "/" + fileName;
        String presignedUrl = s3UtilsService.generatePreSignedObjectUrl(s3Client, filePath);
        return ResponseEntity.ok(presignedUrl);
    }

}

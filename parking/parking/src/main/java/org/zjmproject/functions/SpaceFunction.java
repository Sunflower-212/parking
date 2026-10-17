package org.zjmproject.functions;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zjmproject.pojo.Parking;
import org.zjmproject.pojo.Spaces;
import org.zjmproject.service.ParkingService;
import org.zjmproject.service.SpacesService;

import java.util.List;
import java.util.function.Function;

@Component
public class SpaceFunction implements Function<SpaceFunction.SpaceRequest, List<Spaces>> {

    @Autowired
    private SpacesService spacesService;

    @Autowired
    private ParkingService parkingService;


    @Override
    public List<Spaces> apply(SpaceRequest request) {
        return spacesService.findSpacesByParking(request);
    }

    @Data
    public static class SpaceRequest {
        private Integer parkingId;
        private String parkName;
        private String parkAddress;
    }

}

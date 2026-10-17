package org.zjmproject.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.functions.SpaceFunction;
import org.zjmproject.mapper.SpacesMapper;
import org.zjmproject.pojo.Spaces;
import org.zjmproject.service.SpacesService;

import java.util.List;

@Service
public class SpacesServiceImpl implements SpacesService {

    @Autowired
    private SpacesMapper spacesMapper;
    @Override
    public Spaces findSpacesByInfo(String parkName, Integer spacesRow, Integer spacesCol, Integer spacesStorey) {
        return spacesMapper.findSpacesByInfo(parkName,spacesRow,spacesCol,spacesStorey);
    }

    @Override
    public void addSpace(String parkName, String spacesName, Integer spacesRow, Integer spacesCol, Integer spacesStorey, Integer spacesFlag) {
        spacesMapper.addSpace(parkName,spacesName,spacesRow,spacesCol,spacesStorey,spacesFlag);
    }

    @Override
    public void updateSpace(Integer spacesId, String spacesName, Integer spacesFlag, String userName) {
        spacesMapper.updateSpace(spacesId,spacesName,spacesFlag,userName);
    }

    @Override
    public void userOccupiesSpace(Integer spacesId, String userName) {
        spacesMapper.userOccupiesSpace(spacesId,userName);
    }

    @Override
    public void deleteSpace(Integer spacesId) {
        spacesMapper.deleteSpace(spacesId);
    }

    @Override
    public List<Spaces> getSpacesList(Integer parkId, Integer spacesStorey) {
        return spacesMapper.getSpacesList(parkId, spacesStorey);
    }

    @Override
    public List<Spaces> findSpacesListByParkingId(Integer parkingId) {
        return spacesMapper.findSpacesListByParkingId(parkingId);
    }

    @Override
    public List<Spaces> selectSpaces(Integer parkId, Integer spacesStorey, String spacesName) {
        return spacesMapper.selectSpaces(parkId,spacesStorey,spacesName);
    }

    @Override
    public void deleteSpacesByIds(List<Integer> ids) {
        spacesMapper.deleteSpacesByIds(ids);
    }

    @Override
    public Spaces findSpacesByUserName(String userName, Integer flag) {
        return spacesMapper.findSpacesByUserName(userName,flag);
    }

    @Override
    public List<Spaces> findSpacesByParking(SpaceFunction.SpaceRequest request) {
        return spacesMapper.findSpacesByParking(request);
    }


}

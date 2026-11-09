package org.zjmproject.service;

import org.zjmproject.functions.SpaceFunction;
import org.zjmproject.pojo.Spaces;

import java.util.List;

public interface SpacesService {
    Spaces findSpacesByInfo(String parkName, Integer spacesRow, Integer spacesCol, Integer spacesStorey);

    void addSpace(String parkName, String spacesName, Integer spacesRow, Integer spacesCol, Integer spacesStorey, Integer spacesFlag);


    void updateSpace(Integer spacesId, String spacesName, Integer spacesFlag, String userName);

    void userOccupiesSpace(Integer spacesId, String userName);

    void deleteSpace(Integer spacesId);

    List<Spaces> getSpacesList(Integer parkId, Integer spacesStorey);

    List<Spaces> findSpacesListByParkingId(Integer parkingId);

    List<Spaces> selectSpaces(Integer parkId, Integer spacesStorey, String spacesName);

    void deleteSpacesByIds(List<Integer> ids);

    Spaces findSpacesByUserName(String userName, Integer flag);

    List<Spaces> findSpacesByParking(SpaceFunction.SpaceRequest request);

}

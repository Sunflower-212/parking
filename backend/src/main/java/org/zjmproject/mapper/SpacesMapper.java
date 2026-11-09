package org.zjmproject.mapper;

import org.apache.ibatis.annotations.*;
import org.zjmproject.functions.SpaceFunction;
import org.zjmproject.pojo.Spaces;

import java.util.List;

@Mapper
public interface SpacesMapper {

    Spaces findSpacesByInfo(String parkName, Integer spacesRow, Integer spacesCol, Integer spacesStorey);


    void addSpace(String parkName, String spacesName, Integer spacesRow, Integer spacesCol, Integer spacesStorey, Integer spacesFlag);


    void updateSpace(Integer spacesId, String spacesName, Integer spacesFlag, String userName);

    void userOccupiesSpace(Integer spacesId, String userName);

    @Delete("delete from spaces where id=#{spacesId}")
    void deleteSpace(Integer spacesId);

    List<Spaces> getSpacesList(Integer parkId, Integer spacesStorey);

    @Select("select * from spaces where park_id=#{parkingId}")
    List<Spaces> findSpacesListByParkingId(Integer parkingId);

    List<Spaces> selectSpaces(Integer parkId, Integer spacesStorey, String spacesName);

    void deleteSpacesByIds(List<Integer> ids);

    @Select("select * from spaces where user_name=#{userName} and spaces_flag=#{flag}")
    Spaces findSpacesByUserName(String userName, Integer flag);

    @Update("update spaces set spaces_flag=0, user_name=null where user_name=#{userName}")
    void updateSpaceByUserName(String userName);

    List<Spaces> findSpacesByParking(SpaceFunction.SpaceRequest request);
}

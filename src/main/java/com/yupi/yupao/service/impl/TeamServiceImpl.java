package com.yupi.yupao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.xiaoymin.knife4j.core.util.CollectionUtils;
import com.yupi.yupao.common.ErrorCode;
import com.yupi.yupao.enums.TeamStatusEnum;
import com.yupi.yupao.exception.BusinessException;
import com.yupi.yupao.model.domain.Team;
import com.yupi.yupao.model.domain.User;
import com.yupi.yupao.model.domain.UserTeam;
import com.yupi.yupao.model.dto.TeamQuery;
import com.yupi.yupao.model.request.TeamJoinRequest;
import com.yupi.yupao.model.request.TeamQuitRequest;
import com.yupi.yupao.model.request.TeamUpdateRequest;
import com.yupi.yupao.model.vo.TeamUserVO;
import com.yupi.yupao.model.vo.UserVO;
import com.yupi.yupao.service.TeamService;
import com.yupi.yupao.mapper.TeamMapper;
import com.yupi.yupao.service.UserService;
import com.yupi.yupao.service.UserTeamService;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
* @author Cyqi
* @description 针对表【team(队伍)】的数据库操作Service实现
* @createDate 2024-11-12 18:00:57
*/
@Service
public class TeamServiceImpl extends ServiceImpl<TeamMapper, Team>
    implements TeamService{
    @Resource
    private UserTeamService userTeamService;
    @Resource
    private UserService userService;
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 添加队伍
     * @param team
     * @param logininUser
     * @return
     */
    @Override
    public Long addTeam(Team team, User logininUser) {
        //判断参数是否为空
        if (team==null){
            throw new BusinessException(ErrorCode.NULL_ERROR);
        }
        if (logininUser==null){
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        //校验信息

        final  Long userId = logininUser.getId();
        //a队伍人数>1 且<=20
        Integer maxNum = Optional.ofNullable(team.getMaxNum()).orElse(0);
        if (maxNum<1 || maxNum>20){
            throw  new BusinessException(ErrorCode.PARAMS_ERROR,"队伍人数不符合要求");
        }
        //b.队伍标题
        String name = team.getName();
        if (StringUtils.isBlank(name)){
            throw new BusinessException(ErrorCode.NULL_ERROR,"队伍标题不满足要求");
        }
        //c.描述
        String description = team.getDescription();
        if (StringUtils.isNotBlank(description) && description.length()>512){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍描述过长");
        }
        //d.status是否公开  不选这默认是0
        Integer status = Optional.ofNullable(team.getStatus()).orElse(0);
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
        if (statusEnum==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍状态不满足要求");
        }
        //e.如果status是加密状态 一定要有密码其密码>=32
        String password = team.getPassword();
        if (TeamStatusEnum.SECRET.equals(status)){
            if (StringUtils.isBlank(password) || password.length()>32){
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"密码设置不合适");
            }
        }
        //f.超时时间> 当前时间
        Date expireTime = team.getExpireTime();
        if (new Date().after(expireTime)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"超时时间＞当前时间");
        }
        // g.校验用户最多创建 5个队伍   首先查询数据库
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId",userId);
        long hasTeamNum = this.count(queryWrapper);
        if (hasTeamNum>=5){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户最多创建5个队伍");
        }
        //插入到队伍信息到列表当中
        team.setId(null);
        team.setUserId(userId);
        boolean result = this.save(team);
        Long teamId = team.getId();
        if (!result||teamId==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍插入失败");
        }

        //插入用户   => 队伍关系到关系表
        UserTeam userTeam = new UserTeam();
        userTeam.setUserId(userId);
        userTeam.setTeamId(teamId);
        userTeam.setJoinTime(new Date());
        result = userTeamService.save(userTeam);
        if (!result){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"创建队伍失败");
        }

        return teamId;
    }

    /**
     * 查询队伍
     * @param teamQuery
     * @param isAdmin
     * @return
     * 1. 从请求参数中取出队伍名称等查询条件，如果存在则作为查询条件
     * 2. 不展示已过期的队伍（根据过期时间筛选）
     * 3. 可以通过某个关键词同时对名称和描述查询
     * 4. 只有管理员才能查看加密还有非公开的房间
     * 5. 关联查询已加入队伍的用户信息
     * 6. 关联查询已加入队伍的用户信息
     */
    @Override
    public List<TeamUserVO> listTeams(TeamQuery teamQuery, boolean isAdmin) {
        //查询数据库有什么队伍
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        //判断参数是否为空  这里做一个优化，如果我们设置参数为空的化我们直接生成一些队伍列表，如果直接为空白页面不好看
        //因此我们写成不为空
        if (teamQuery!=null){
            //分别查询不一样的条件
            Long id = teamQuery.getId();
            if (id !=null && id>0){
                queryWrapper.eq("id",id);
            }
            //因为上面使用VO
            List<Long> idList = teamQuery.getIdList();
            if (CollectionUtils.isNotEmpty(idList)){
                queryWrapper.in("id",idList);
            }
            String searchText = teamQuery.getSearchText();
            if (StringUtils.isNotBlank(searchText)){
                queryWrapper.and(qw -> qw.like("name",searchText).or().like("description",searchText));
            }
            String name = teamQuery.getName();
            if (StringUtils.isNotBlank(searchText)){
                queryWrapper.like("name",name);
            }
            String  description= teamQuery.getDescription();
            if (StringUtils.isNotBlank(description)){
                queryWrapper.like("description",description);
            }
            Integer maxNum = teamQuery.getMaxNum();
            if (maxNum !=null && maxNum >0){
                queryWrapper.eq("maxNum",maxNum);
            }
            Long userId = teamQuery.getUserId();
            if (userId !=null){
                queryWrapper.eq("UserId",userId);
            }
            //根据状态来查询
            Integer status = teamQuery.getStatus();
            TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
            if (statusEnum==null){
                statusEnum=TeamStatusEnum.PUBLIC;
            }
            if (!isAdmin && statusEnum.equals(TeamStatusEnum.PRIVATE)){
                throw new BusinessException(ErrorCode.NO_AUTH);
            }
            queryWrapper.eq("status",statusEnum.getValue());

        }
        //不展示过期的队伍
        queryWrapper.and(qw -> qw.gt("expireTime",new Date()).or().isNull("expireTime"));
        List<Team> teamList = this.list(queryWrapper);
        if (CollectionUtils.isEmpty(teamList)){
            return new ArrayList<>();
        }
        ArrayList<TeamUserVO> teamUserOVList = new ArrayList<>();
        //关联关键创建人信息
        for (Team team : teamList) {
            Long userId = team.getUserId();
            if (userId==null){
                continue;
            }
            User user = userService.getById(userId);
            TeamUserVO teamUserVO = new TeamUserVO();
            //拷贝
            BeanUtils.copyProperties(team,teamUserVO);
            //用户脱敏
            if (user==null){
                UserVO userVO = new UserVO();
                BeanUtils.copyProperties(user, userVO);
                teamUserVO.setCreateUser(userVO);
            }
            teamUserOVList.add(teamUserVO);

        }
        return teamUserOVList;
    }

    /**
     * 更新队伍信息
     * @param teamUpdateRequest
     * @param loginUser
     * @return
     */
    @Override
    public Boolean updateTeam(TeamUpdateRequest teamUpdateRequest, User loginUser) {
        //是否参数为空
        if(teamUpdateRequest==null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        Long id = teamUpdateRequest.getId();
        if (id==null || id <=0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //根据id查询队伍存不存在
        Team oldTeam = this.getById(id);
        if (oldTeam==null){
            throw new BusinessException(ErrorCode.NULL_ERROR,"队伍不存在");
        }
        //校验身份
        if (oldTeam.getUserId() !=loginUser.getId() && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH);
        }

        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(teamUpdateRequest.getStatus());
        if (statusEnum.equals(TeamStatusEnum.SECRET)){
            if (StringUtils.isBlank(teamUpdateRequest.getPassword())){
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"加密房间必须输入密码");
            }
        }
        Team updateTeam = new Team();
        BeanUtils.copyProperties(teamUpdateRequest, updateTeam);
        return this.updateById(updateTeam);

    }

    /**
     * 加入队伍
     * @param teamJoinRequest
     * @param loginUser
     * @return
     * 其他人、未满、未过期，允许加入多个队伍，但是要有个上限 P0
     * 1. 用户最多加入 5 个队伍
     * 2. 队伍必须存在，只能加入未满、未过期的队伍
     * 3. 不能加入自己的队伍，不能重复加入已加入的队伍（幂等性）
     * 4. 禁止加入私有的队伍
     * 5. 如果加入的队伍是加密的，必须密码匹配才可以
     * 6. 新增队伍 - 用户关联信息
     */
    @Override
    public boolean joinTeam(TeamJoinRequest teamJoinRequest, User loginUser) {
        //判断参数是否有误或者空
        if (teamJoinRequest==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long teamId = teamJoinRequest.getTeamId();
        if (teamId ==null || teamId<=0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team team = this.getById(teamId);
        if (team ==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍不存在");
        }
        Date expireTime = team.getExpireTime();
        if (expireTime !=null && expireTime.before(new Date())){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍已过期");
        }
        Integer status = team.getStatus();
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
        if (TeamStatusEnum.PRIVATE.equals(statusEnum)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"禁止加入私有队伍");
        }
        String password = teamJoinRequest.getPassword();
        if (TeamStatusEnum.SECRET.equals(statusEnum)){
            if (StringUtils.isBlank(password) || !password.equals(team.getPassword())){
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"密码错误");
            }
        }
        //该用户已加入的队伍数量
        Long userId = loginUser.getId();
        RLock lock = redissonClient.getLock("yupao:join_team");
        try{
            //执行锁并行
            while (true){
                if (lock.tryLock(0,-1, TimeUnit.MILLISECONDS)){
                    System.out.println("getLock"+Thread.currentThread().getId());
                    QueryWrapper<UserTeam> userTeamQueryWrapper = new QueryWrapper<>();
                    userTeamQueryWrapper.eq("userId",userId);
                    long hasJoinNum = userTeamService.count(userTeamQueryWrapper);
                    if (hasJoinNum>5){
                        throw new BusinessException(ErrorCode.PARAMS_ERROR,"最多加入五支队伍");
                    }
                    //不能加入重复的队伍
                    userTeamQueryWrapper = new QueryWrapper<>();
                    userTeamQueryWrapper.eq("userId",userId);
                    userTeamQueryWrapper.eq("teamId",teamId);
                    long hasUserJoinTeam=userTeamService.count(userTeamQueryWrapper);
                    if (hasUserJoinTeam>0){
                        throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户已加入该队伍");
                    }
                    //判断队伍是否已经满了
                    userTeamQueryWrapper = new QueryWrapper<>();
                    userTeamQueryWrapper.eq("teamId",teamId);
                    long hasUserJoinNum=userTeamService.count(userTeamQueryWrapper);
                    if (hasUserJoinNum>team.getMaxNum()){
                        throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍已满");
                    }
                    //加入修改信息
                    UserTeam userTeam = new UserTeam();
                    userTeam.setUserId(userId);
                    userTeam.setTeamId(teamId);
                    userTeam.setJoinTime(new Date());
                    return userTeamService.save(userTeam);
                }
            }
        }catch (InterruptedException e){
            log.error("doCacheRecommendUser error",e);
            return false;
        }finally {
            if (lock.isHeldByCurrentThread()){
                System.out.println("unlock:"+Thread.currentThread().getId());
                lock.unlock();
            }
        }

    }

    /**
     * 退出队伍
     * @param teamQuitRequest
     * @param logininUser
     * @return
     * 请求参数：队伍 id
     * 1.  校验请求参数
     * 2.  校验队伍是否存在
     * 3.  校验我是否已加入队伍
     * 4.  如果队伍
     *   a.  只剩一人，队伍解散
     *   b.  还有其他人
     *     ⅰ.  如果是队长退出队伍，权限转移给第二早加入的用户 —— 先来后到
     * 只用取 id 最小的 2 条数据
     *    	 ⅱ.  非队长，自己退出队伍
     */
    @Override
    public boolean quitTeam(TeamQuitRequest teamQuitRequest, User logininUser) {
        if (teamQuitRequest==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"teamQuitRequest 不能为空");
        }
        Long teamId = teamQuitRequest.getTeamId();
        if (teamId==null||teamId<=0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"无效的 teamId");
        }
        //查看队伍是否存在
        Team team = this.getById(teamId);
        if (team==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"队伍不存现在");
        }
        //检查我是否在队伍当中
        Long userId = logininUser.getId();
        UserTeam queryUserTeam = new UserTeam();
        queryUserTeam.setUserId(userId);
        queryUserTeam.setTeamId(teamId);
        QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>(queryUserTeam);
        long count = userTeamService.count(queryWrapper);
        if (count<0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"未加入队伍");
        }
        //判断队伍人数有多少人，如果 = 0 解散
       // queryWrapper.eq("teamId",teamId);
        long teamHasJoinNum = countTeamUserByTeamId(teamId);
        if (teamHasJoinNum==1){
            //删除队伍
            this.removeById(teamId);
        }else {
            //队伍剩下两个或者以上的人数时
            //判断自己是不是队长 是队长 把队伍转移到最早加入的用户
            if (team.getUserId()==userId){
                //查询该队伍所有用户加入时间
                QueryWrapper<UserTeam> userTeamQueryWrapper = new QueryWrapper<>();
                userTeamQueryWrapper.eq("teamId",teamId);
                userTeamQueryWrapper.last("order by id asc limit 2");
                List<UserTeam> userTeamList = userTeamService.list(userTeamQueryWrapper);
                if (CollectionUtils.isEmpty(userTeamList)  || userTeamList.size()<=1){
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR);
                }
                UserTeam nextUserTeam = userTeamList.get(1);
                Long nextTeamLeaderId = nextUserTeam.getUserId();
                //更新队长
                Team updateTeam = new Team();
                updateTeam.setId(teamId);
                updateTeam.setUserId(nextTeamLeaderId);
                boolean result = this.updateById(updateTeam);
                if (!result){
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,"退出失败");
                }
            }
        }

        //解除关系
        return userTeamService.remove(queryWrapper);
    }

    /**
     * 删除队伍
     * @param id
     * @param logininUser
     * @return
     * 校验参数
     * 检验队伍是否存在
     * 校验你自己是否为队长
     * 移除所加入的信息
     * 删除队伍
     * 添加事务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTeam(long id, User logininUser) {
        if (id<0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //查看队伍是否存在
        Team team = this.getById(id);
        Long teamId = team.getId();
        if (teamId==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (team.getUserId() !=logininUser.getId()){
            throw new BusinessException(ErrorCode.NO_AUTH,"无权访问");
        }
        //移除所有加入队伍的关联信息
        QueryWrapper<UserTeam> userTeamQueryWrapper = new QueryWrapper<>();
        userTeamQueryWrapper.eq("teamId",teamId);
        boolean result = userTeamService.remove(userTeamQueryWrapper);
        if (!result) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"删除队伍关联信息失败");
        }
        return this.removeById(teamId);
    }

    //查寻队伍人数
    private long countTeamUserByTeamId(long teamId){
        QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("teamId",teamId);
        return userTeamService.count(queryWrapper);
    }
}





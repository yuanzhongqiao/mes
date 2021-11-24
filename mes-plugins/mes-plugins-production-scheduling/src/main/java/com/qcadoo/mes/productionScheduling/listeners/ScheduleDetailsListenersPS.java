package com.qcadoo.mes.productionScheduling.listeners;

import static com.qcadoo.mes.orders.states.constants.OperationalTaskStateStringValues.REJECTED;
import static com.qcadoo.model.api.search.SearchProjections.alias;
import static com.qcadoo.model.api.search.SearchProjections.list;
import static com.qcadoo.model.api.search.SearchProjections.rowCount;
import static java.util.Map.Entry.comparingByValue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.basic.constants.ProductFamilyElementType;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.basic.constants.WorkstationFields;
import com.qcadoo.mes.basic.constants.WorkstationTypeFields;
import com.qcadoo.mes.operationTimeCalculations.OperationWorkTime;
import com.qcadoo.mes.operationTimeCalculations.OperationWorkTimeService;
import com.qcadoo.mes.orders.constants.OperationalTaskFields;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ScheduleFields;
import com.qcadoo.mes.orders.constants.SchedulePositionFields;
import com.qcadoo.mes.orders.constants.ScheduleSortOrder;
import com.qcadoo.mes.orders.constants.ScheduleWorkstationAssignCriterion;
import com.qcadoo.mes.orders.listeners.ScheduleDetailsListeners;
import com.qcadoo.mes.productionLines.constants.WorkstationFieldsPL;
import com.qcadoo.mes.productionScheduling.constants.OperCompTimeCalculation;
import com.qcadoo.mes.technologies.ProductQuantitiesService;
import com.qcadoo.mes.technologies.TechnologyService;
import com.qcadoo.mes.technologies.constants.AssignedToOperation;
import com.qcadoo.mes.technologies.constants.OperationProductOutComponentFields;
import com.qcadoo.mes.technologies.constants.TechnologyFields;
import com.qcadoo.mes.technologies.constants.TechnologyOperationComponentFields;
import com.qcadoo.mes.technologies.dto.OperationProductComponentWithQuantityContainer;
import com.qcadoo.mes.timeNormsForOperations.constants.TechOperCompWorkstationTimeFields;
import com.qcadoo.mes.timeNormsForOperations.constants.TechnologyOperationComponentFieldsTNFO;
import com.qcadoo.model.api.BigDecimalUtils;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.JoinType;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchProjections;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.plugin.api.PluginManager;
import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FormComponent;
import com.qcadoo.view.api.components.GridComponent;

@Service
public class ScheduleDetailsListenersPS {

    private static final String L_ORDERS = "orders";

    private static final String SCHEDULE_ID = "scheduleId";

    private static final String ORDERS_FOR_SUBPRODUCTS_GENERATION = "ordersForSubproductsGeneration";

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ProductQuantitiesService productQuantitiesService;

    @Autowired
    private OperationWorkTimeService operationWorkTimeService;

    @Autowired
    private TechnologyService technologyService;

    @Autowired
    private ScheduleDetailsListeners scheduleDetailsListeners;

    @Autowired
    private ShiftsService shiftsService;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private PluginManager pluginManager;

    @Transactional
    public void generatePlan(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        getOperations(view, state, args);
        assignOperationsToWorkstations(view, state, args);
        scheduleDetailsListeners.assignWorkersToOperations(view, state, args);
    }

    @Transactional
    public void getOperations(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        GridComponent ordersGrid = (GridComponent) view.getComponentByReference(L_ORDERS);
        List<Entity> orders = ordersGrid.getEntities();
        FormComponent formComponent = (FormComponent) state;
        Entity schedule = formComponent.getEntity();
        boolean includeTpz = schedule.getBooleanField(ScheduleFields.INCLUDE_TPZ);
        DataDefinition schedulePositionDD = dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_SCHEDULE_POSITION);
        List<Entity> positions = Lists.newArrayList();
        for (Entity order : orders) {
            Entity technology = order.getBelongsToField(OrderFields.TECHNOLOGY);
            if (technology == null) {
                continue;
            }
            final Map<Long, BigDecimal> operationRuns = Maps.newHashMap();

            OperationProductComponentWithQuantityContainer operationProductComponentWithQuantityContainer = productQuantitiesService
                    .getProductComponentQuantities(technology, order.getDecimalField(OrderFields.PLANNED_QUANTITY),
                            operationRuns);
            List<Entity> operationComponents = technology.getHasManyField(TechnologyFields.OPERATION_COMPONENTS);
            for (Entity operationComponent : operationComponents) {
                BigDecimal operationComponentRuns = BigDecimalUtils
                        .convertNullToZero(operationRuns.get(operationComponent.getId()));
                OperationWorkTime operationWorkTime = operationWorkTimeService.estimateTechOperationWorkTime(operationComponent,
                        operationComponentRuns, includeTpz, false, false);
                Entity schedulePosition = createSchedulePosition(schedule, schedulePositionDD, order, operationComponent,
                        operationWorkTime, operationProductComponentWithQuantityContainer, operationComponentRuns);
                positions.add(schedulePosition);
            }
        }

        schedule.setField(ScheduleFields.POSITIONS, positions);
        schedule = schedule.getDataDefinition().save(schedule);
        formComponent.setEntity(schedule);
        view.addMessage("productionScheduling.info.schedulePositionsGenerated", ComponentState.MessageType.SUCCESS);
    }

    private Entity createSchedulePosition(Entity schedule, DataDefinition schedulePositionDD, Entity order,
            Entity technologyOperationComponent, OperationWorkTime operationWorkTime,
            OperationProductComponentWithQuantityContainer operationProductComponentWithQuantityContainer,
            BigDecimal operationComponentRuns) {
        Entity schedulePosition = schedulePositionDD.create();
        schedulePosition.setField(SchedulePositionFields.SCHEDULE, schedule);
        schedulePosition.setField(OrdersConstants.MODEL_ORDER, order);
        schedulePosition.setField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT, technologyOperationComponent);
        Entity mainOutputProductComponent = technologyService.getMainOutputProductComponent(technologyOperationComponent);
        Entity product = mainOutputProductComponent.getBelongsToField(OperationProductOutComponentFields.PRODUCT);
        if (ProductFamilyElementType.PRODUCTS_FAMILY.getStringValue().equals(product.getField(ProductFields.ENTITY_TYPE))) {
            product = order.getBelongsToField(OrderFields.PRODUCT);
        }
        schedulePosition.setField(SchedulePositionFields.PRODUCT, product);
        schedulePosition.setField(SchedulePositionFields.QUANTITY,
                operationProductComponentWithQuantityContainer.get(mainOutputProductComponent));
        schedulePosition.setField(SchedulePositionFields.ADDITIONAL_TIME,
                technologyOperationComponent.getIntegerField(TechnologyOperationComponentFieldsTNFO.TIME_NEXT_OPERATION));
        schedulePosition.setField(OperCompTimeCalculation.LABOR_WORK_TIME, operationWorkTime.getLaborWorkTime());
        schedulePosition.setField(SchedulePositionFields.MACHINE_WORK_TIME, operationWorkTime.getMachineWorkTime());
        schedulePosition.setField(SchedulePositionFields.OPERATION_RUNS, operationComponentRuns);
        return schedulePosition;
    }

    @Transactional
    public void assignOperationsToWorkstations(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        Entity schedule = ((FormComponent) state).getEntity();
        Map<Long, Date> workstationsFinishDates = Maps.newHashMap();
        Set<Long> ordersToAvoid = Sets.newHashSet();
        List<Long> positionsIds = sortPositionsForWorkstations(schedule.getId());
        Date scheduleStartTime = schedule.getDateField(ScheduleFields.START_TIME);
        for (Long positionId : positionsIds) {
            Entity position = dataDefinitionService
                    .get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_SCHEDULE_POSITION).get(positionId);
            if (ordersToAvoid.contains(position.getBelongsToField(SchedulePositionFields.ORDER).getId())) {
                continue;
            }
            List<Entity> workstations = getWorkstationsFromTOC(position);
            if (workstations.isEmpty()) {
                ordersToAvoid.add(position.getBelongsToField(SchedulePositionFields.ORDER).getId());
                continue;
            }
            Map<Long, Date> operationWorkstationsFinishDates = Maps.newHashMap();
            Map<Long, Date> operationWorkstationsStartDates = Maps.newHashMap();
            Map<Long, Integer> operationWorkstationMachineWorkTimes = Maps.newHashMap();
            Map<Long, Integer> operationWorkstationAdditionalTimes = Maps.newHashMap();

            boolean allMachineWorkTimesEqualsZero = getWorkstationsNewFinishDate(workstationsFinishDates, scheduleStartTime,
                    position, workstations, operationWorkstationsFinishDates, operationWorkstationsStartDates,
                    operationWorkstationMachineWorkTimes, operationWorkstationAdditionalTimes);

            if (allMachineWorkTimesEqualsZero) {
                ordersToAvoid.add(position.getBelongsToField(SchedulePositionFields.ORDER).getId());
                continue;
            }

            if (ScheduleWorkstationAssignCriterion.SHORTEST_TIME.getStringValue()
                    .equals(schedule.getStringField(ScheduleFields.WORKSTATION_ASSIGN_CRITERION))) {
                operationWorkstationsFinishDates.entrySet().stream().min(comparingByValue())
                        .ifPresent(entry -> updatePositionWorkstationAndDates(entry, workstationsFinishDates, position,
                                operationWorkstationsStartDates.get(entry.getKey()),
                                operationWorkstationMachineWorkTimes.get(entry.getKey()),
                                operationWorkstationAdditionalTimes.get(entry.getKey())));
            } else {
                Map.Entry<Long, Date> firstEntry;
                if (workstationsFinishDates.isEmpty()) {
                    firstEntry = operationWorkstationsFinishDates.entrySet().iterator().next();
                } else {
                    firstEntry = operationWorkstationsFinishDates.entrySet().stream()
                            .filter(entry -> workstationsFinishDates.containsKey(entry.getKey())).findFirst()
                            .orElse(operationWorkstationsFinishDates.entrySet().iterator().next());
                }
                updatePositionWorkstationAndDates(firstEntry, workstationsFinishDates, position,
                        operationWorkstationsStartDates.get(firstEntry.getKey()),
                        operationWorkstationMachineWorkTimes.get(firstEntry.getKey()),
                        operationWorkstationAdditionalTimes.get(firstEntry.getKey()));
            }
        }
    }

    private List<Entity> getWorkstationsFromTOC(Entity position) {
        Entity technologyOperationComponent = position.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT);
        List<Entity> workstations;
        if (AssignedToOperation.WORKSTATIONS.getStringValue()
                .equals(technologyOperationComponent.getStringField(TechnologyOperationComponentFields.ASSIGNED_TO_OPERATION))) {
            workstations = technologyOperationComponent.getManyToManyField(TechnologyOperationComponentFields.WORKSTATIONS);
        } else {
            Entity workstationType = technologyOperationComponent
                    .getBelongsToField(TechnologyOperationComponentFields.WORKSTATION_TYPE);
            if (workstationType == null) {
                workstations = Collections.emptyList();
            } else {
                workstations = workstationType.getHasManyField(WorkstationTypeFields.WORKSTATIONS);
            }
        }
        if (position.getBelongsToField(SchedulePositionFields.SCHEDULE).getBooleanField(ScheduleFields.SCHEDULE_FOR_BUFFER)) {
            List<Entity> bufferWorkstations = workstations.stream().filter(e -> e.getBooleanField(WorkstationFields.BUFFER))
                    .collect(Collectors.toList());
            if (!bufferWorkstations.isEmpty()) {
                return bufferWorkstations;
            }
        }
        return workstations;
    }

    private boolean getWorkstationsNewFinishDate(Map<Long, Date> workstationsFinishDates, Date scheduleStartTime, Entity position,
            List<Entity> workstations, Map<Long, Date> operationWorkstationsFinishDates,
            Map<Long, Date> operationWorkstationsStartDates, Map<Long, Integer> operationWorkstationMachineWorkTimes,
            Map<Long, Integer> operationWorkstationAdditionalTimes) {
        Entity schedule = position.getBelongsToField(SchedulePositionFields.SCHEDULE);
        boolean allMachineWorkTimesEqualsZero = true;
        for (Entity workstation : workstations) {
            Integer machineWorkTime = position.getIntegerField(SchedulePositionFields.MACHINE_WORK_TIME);
            Integer additionalTime = position.getIntegerField(SchedulePositionFields.ADDITIONAL_TIME);
            Optional<Entity> techOperCompWorkstationTime = getTechOperCompWorkstationTime(position, workstation);
            if (techOperCompWorkstationTime.isPresent()) {
                OperationWorkTime operationWorkTime = operationWorkTimeService.estimateTechOperationWorkTimeForWorkstation(
                        position.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT),
                        position.getDecimalField(SchedulePositionFields.OPERATION_RUNS),
                        schedule.getBooleanField(ScheduleFields.INCLUDE_TPZ), false, techOperCompWorkstationTime.get());
                machineWorkTime = operationWorkTime.getMachineWorkTime();
                additionalTime = techOperCompWorkstationTime.get()
                        .getIntegerField(TechOperCompWorkstationTimeFields.TIME_NEXT_OPERATION);
            }
            if (machineWorkTime == 0) {
                continue;
            } else {
                allMachineWorkTimesEqualsZero = false;
            }
            Date finishDate = getFinishDate(workstationsFinishDates, scheduleStartTime, schedule, workstation);
            List<Entity> children = getChildren(position.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT),
                    position.getBelongsToField(SchedulePositionFields.ORDER), schedule);
            if (pluginManager.isPluginEnabled(ORDERS_FOR_SUBPRODUCTS_GENERATION)) {
                children.addAll(getOrderChildren(position.getBelongsToField(SchedulePositionFields.ORDER), schedule));
            }
            for (Entity child : children) {
                Date childEndTime = child.getDateField(SchedulePositionFields.END_TIME);
                if (!schedule.getBooleanField(ScheduleFields.ADDITIONAL_TIME_EXTENDS_OPERATION)) {
                    childEndTime = Date.from(
                            childEndTime.toInstant().plusSeconds(child.getIntegerField(SchedulePositionFields.ADDITIONAL_TIME)));
                }
                if (childEndTime.after(finishDate)) {
                    finishDate = childEndTime;
                }
            }
            DateTime finishDateTime = new DateTime(finishDate);
            Date newStartDate = shiftsService
                    .getNearestWorkingDate(finishDateTime, workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE))
                    .orElse(finishDateTime).toDate();

            Date newFinishDate = shiftsService.findDateToForProductionLine(newStartDate, machineWorkTime,
                    workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE));
            if (schedule.getBooleanField(ScheduleFields.ADDITIONAL_TIME_EXTENDS_OPERATION)) {
                newFinishDate = Date.from(newFinishDate.toInstant().plusSeconds(additionalTime));
            }
            operationWorkstationsStartDates.put(workstation.getId(), newStartDate);
            operationWorkstationsFinishDates.put(workstation.getId(), newFinishDate);
            operationWorkstationMachineWorkTimes.put(workstation.getId(), machineWorkTime);
            operationWorkstationAdditionalTimes.put(workstation.getId(), additionalTime);
        }
        return allMachineWorkTimesEqualsZero;
    }

    private Optional<Entity> getTechOperCompWorkstationTime(Entity position, Entity workstation) {
        Entity technologyOperationComponent = position.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT);
        List<Entity> techOperCompWorkstationTimes = technologyOperationComponent
                .getHasManyField(TechnologyOperationComponentFieldsTNFO.TECH_OPER_COMP_WORKSTATION_TIMES);
        for (Entity techOperCompWorkstationTime : techOperCompWorkstationTimes) {
            if (techOperCompWorkstationTime.getBelongsToField(TechOperCompWorkstationTimeFields.WORKSTATION).getId()
                    .equals(workstation.getId())) {
                return Optional.of(techOperCompWorkstationTime);
            }
        }
        return Optional.empty();
    }

    private Date getFinishDate(Map<Long, Date> workstationsFinishDates, Date scheduleStartTime, Entity schedule,
            Entity workstation) {
        Date finishDate;
        if (schedule.getBooleanField(ScheduleFields.SCHEDULE_FOR_BUFFER)
                && workstation.getBooleanField(WorkstationFields.BUFFER)) {
            finishDate = scheduleStartTime;
        } else {
            finishDate = workstationsFinishDates.get(workstation.getId());
            if (finishDate == null) {
                Date operationalTasksMaxFinishDate = getOperationalTasksMaxFinishDateForWorkstation(scheduleStartTime,
                        workstation);
                if (operationalTasksMaxFinishDate != null) {
                    finishDate = operationalTasksMaxFinishDate;
                    workstationsFinishDates.put(workstation.getId(), finishDate);
                }
            }
            if (finishDate == null) {
                finishDate = scheduleStartTime;
            }
        }
        return finishDate;
    }

    private List<Entity> getChildren(Entity technologyOperationComponent, Entity order, Entity schedule) {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_SCHEDULE_POSITION).find()
                .createAlias(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT, "toc", JoinType.INNER)
                .add(SearchRestrictions.belongsTo("toc." + TechnologyOperationComponentFields.PARENT,
                        technologyOperationComponent))
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.ORDER, order))
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.SCHEDULE, schedule)).list().getEntities();
    }

    private List<Entity> getOrderChildren(Entity order, Entity schedule) {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_SCHEDULE_POSITION).find()
                .createAlias(SchedulePositionFields.ORDER, "o", JoinType.INNER)
                .add(SearchRestrictions.belongsTo("o.parent", order))
                .createAlias(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT, "toc", JoinType.INNER)
                .add(SearchRestrictions.isNull("toc." + TechnologyOperationComponentFields.PARENT))
                .add(SearchRestrictions.isNotNull(SchedulePositionFields.END_TIME))
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.SCHEDULE, schedule)).list().getEntities();
    }

    private Date getOperationalTasksMaxFinishDateForWorkstation(Date scheduleStartTime, Entity workstation) {
        Entity operationalTasksMaxFinishDateEntity = dataDefinitionService
                .get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_OPERATIONAL_TASK).find()
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.WORKSTATION, workstation))
                .add(SearchRestrictions.ne(OperationalTaskFields.STATE, REJECTED))
                .add(SearchRestrictions.gt(OperationalTaskFields.FINISH_DATE, scheduleStartTime))
                .setProjection(list()
                        .add(alias(SearchProjections.max(OperationalTaskFields.FINISH_DATE), OperationalTaskFields.FINISH_DATE))
                        .add(rowCount()))
                .addOrder(SearchOrders.desc(OperationalTaskFields.FINISH_DATE)).setMaxResults(1).uniqueResult();
        return operationalTasksMaxFinishDateEntity.getDateField(OperationalTaskFields.FINISH_DATE);
    }

    private void updatePositionWorkstationAndDates(Map.Entry<Long, Date> entry, Map<Long, Date> workstationsFinishDates,
            Entity position, Date startTime, Integer machineWorkTime, Integer additionalTime) {
        workstationsFinishDates.put(entry.getKey(), entry.getValue());
        position.setField(SchedulePositionFields.WORKSTATION, entry.getKey());
        position.setField(SchedulePositionFields.START_TIME, startTime);
        position.setField(SchedulePositionFields.END_TIME, entry.getValue());
        position.setField(SchedulePositionFields.STAFF, null);
        position.setField(SchedulePositionFields.MACHINE_WORK_TIME, machineWorkTime);
        position.setField(SchedulePositionFields.ADDITIONAL_TIME, additionalTime);
        position.getDataDefinition().save(position);
    }

    private List<Long> sortPositionsForWorkstations(Long scheduleId) {
        Entity schedule = dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_SCHEDULE)
                .get(scheduleId);
        Map<String, Object> parameters = Maps.newHashMap();
        parameters.put(SCHEDULE_ID, scheduleId);
        StringBuilder query = new StringBuilder();
        query.append("SELECT sp.id FROM orders_scheduleposition sp JOIN technologies_technologyoperationcomponent toc ");
        query.append("ON sp.technologyoperationcomponent_id = toc.id JOIN orders_order o ON sp.order_id = o.id ");
        query.append("WHERE sp.schedule_id = :scheduleId ORDER BY ");
        query.append("string_to_array(regexp_replace(SPLIT_PART(o.number, '-', 2), '[^0-9.]', '0', 'g'), '.')::int[] desc, ");
        query.append("string_to_array(regexp_replace(rtrim(toc.nodenumber, '.'), '[^0-9.]', '0', 'g'), '.')::int[] desc, ");
        if (ScheduleSortOrder.DESCENDING.getStringValue().equals(schedule.getStringField(ScheduleFields.SORT_ORDER))) {
            query.append("sp.machineworktime desc");
        } else {
            query.append("sp.machineworktime asc");
        }
        return jdbcTemplate.queryForList(query.toString(), parameters, Long.class);
    }

}

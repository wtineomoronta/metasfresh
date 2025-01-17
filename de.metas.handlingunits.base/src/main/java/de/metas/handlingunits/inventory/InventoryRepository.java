
package de.metas.handlingunits.inventory;

import static org.adempiere.model.InterfaceWrapperHelper.load;
import static org.adempiere.model.InterfaceWrapperHelper.loadByRepoIdAwares;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;
import static org.compiere.util.Util.coalesceSuppliers;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.mm.attributes.AttributeSetInstanceId;
import org.adempiere.mm.attributes.api.AttributesKeys;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.OrgId;
import org.adempiere.warehouse.LocatorId;
import org.adempiere.warehouse.api.IWarehouseDAO;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Inventory;
import org.springframework.stereotype.Repository;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;

import de.metas.document.DocBaseAndSubType;
import de.metas.document.DocTypeId;
import de.metas.document.IDocTypeDAO;
import de.metas.handlingunits.HuId;
import de.metas.handlingunits.inventory.InventoryLine.InventoryLineBuilder;
import de.metas.handlingunits.model.I_M_InventoryLine;
import de.metas.handlingunits.model.I_M_InventoryLine_HU;
import de.metas.inventory.HUAggregationType;
import de.metas.inventory.IInventoryDAO;
import de.metas.inventory.InventoryId;
import de.metas.inventory.InventoryLineId;
import de.metas.material.event.commons.AttributesKey;
import de.metas.product.ProductId;
import de.metas.quantity.Quantity;
import de.metas.uom.IUOMConversionBL;
import de.metas.uom.IUOMDAO;
import de.metas.uom.UOMConversionContext;
import de.metas.uom.UomId;
import de.metas.util.GuavaCollectors;
import de.metas.util.Services;
import lombok.NonNull;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2019 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Repository
public class InventoryRepository
{
	private final IUOMDAO uomsRepo = Services.get(IUOMDAO.class);
	private final IUOMConversionBL convBL = Services.get(IUOMConversionBL.class);
	private final IWarehouseDAO warehousesRepo = Services.get(IWarehouseDAO.class);
	private final IQueryBL queryBL = Services.get(IQueryBL.class);

	private I_M_InventoryLine getInventoryLineRecordById(@Nullable final InventoryLineId inventoryLineId)
	{
		return load(inventoryLineId, I_M_InventoryLine.class);
	}

	@Deprecated
	final I_M_InventoryLine getInventoryLineRecordFor(@NonNull final InventoryLine inventoryLine)
	{
		return getInventoryLineRecordById(inventoryLine.getId());
	}

	private HashMap<InventoryLineId, I_M_InventoryLine> getInventoryLineRecordsByIds(@Nullable final Set<InventoryLineId> inventoryLineIds)
	{
		return loadByRepoIdAwares(inventoryLineIds, I_M_InventoryLine.class)
				.stream()
				.collect(GuavaCollectors.toHashMapByKey(record -> extractInventoryLineId(record)));
	}

	private static InventoryLineId extractInventoryLineId(@NonNull final I_M_InventoryLine record)
	{
		return InventoryLineId.ofRepoId(record.getM_InventoryLine_ID());
	}

	private static InventoryLineId extractInventoryLineIdOrNull(@NonNull final I_M_InventoryLine record)
	{
		return InventoryLineId.ofRepoIdOrNull(record.getM_InventoryLine_ID());
	}

	public Inventory getById(@NonNull final InventoryId inventoryId)
	{
		final I_M_Inventory inventoryRecord = Services.get(IInventoryDAO.class).getById(inventoryId);
		return toInventory(inventoryRecord);
	}

	public Inventory toInventory(@NonNull final I_M_Inventory inventoryRecord)
	{
		final InventoryId inventoryId = InventoryId.ofRepoId(inventoryRecord.getM_Inventory_ID());
		final DocBaseAndSubType docBaseAndSubType = extractDocBaseAndSubTypeOrNull(inventoryRecord); // shall not be null at this point
		if (docBaseAndSubType == null)
		{
			throw new AdempiereException("Failed extracting DocBaseType and DocSubType from " + inventoryRecord);
		}

		final Collection<I_M_InventoryLine> inventoryLineRecords = retrieveLineRecords(inventoryId);
		final ImmutableSet<InventoryLineId> inventoryLineIds = inventoryLineRecords.stream().map(r -> extractInventoryLineId(r)).collect(ImmutableSet.toImmutableSet());

		final ListMultimap<InventoryLineId, I_M_InventoryLine_HU> inventoryLineHURecords = retrieveInventoryLineHURecords(inventoryLineIds);

		final List<InventoryLine> inventoryLines = inventoryLineRecords
				.stream()
				.map(inventoryLineRecord -> toInventoryLine(inventoryLineRecord, inventoryLineHURecords))
				.collect(ImmutableList.toImmutableList());

		return Inventory.builder()
				.id(inventoryId)
				.docBaseAndSubType(docBaseAndSubType)
				.lines(inventoryLines)
				.build();
	}

	public static DocBaseAndSubType extractDocBaseAndSubTypeOrNull(@Nullable final I_M_Inventory inventoryRecord)
	{
		if (inventoryRecord == null)
		{
			return null; // nothing to extract
		}

		final DocTypeId docTypeId = DocTypeId.ofRepoIdOrNull(inventoryRecord.getC_DocType_ID());
		if (docTypeId == null)
		{
			return null; // nothing to extract
		}

		return Services.get(IDocTypeDAO.class).getDocBaseAndSubTypeById(docTypeId);
	}

	public InventoryLine toInventoryLine(@NonNull final I_M_InventoryLine inventoryLineRecord)
	{
		final InventoryLineId inventoryLineId = extractInventoryLineIdOrNull(inventoryLineRecord);
		final List<I_M_InventoryLine_HU> inventoryLineHURecords = inventoryLineId != null
				? retrieveInventoryLineHURecords(inventoryLineId)
				: ImmutableList.of();

		return toInventoryLine(inventoryLineRecord, inventoryLineHURecords);
	}

	private InventoryLine toInventoryLine(
			@NonNull final I_M_InventoryLine inventoryLineRecord,
			@NonNull final ListMultimap<InventoryLineId, I_M_InventoryLine_HU> inventoryLineHURecordsMap)
	{
		final InventoryLineId inventoryLineId = extractInventoryLineIdOrNull(inventoryLineRecord);
		final List<I_M_InventoryLine_HU> inventoryLineHURecords = inventoryLineId != null
				? inventoryLineHURecordsMap.get(inventoryLineId)
				: ImmutableList.of();

		return toInventoryLine(inventoryLineRecord, inventoryLineHURecords);
	}

	private InventoryLine toInventoryLine(
			@NonNull final I_M_InventoryLine inventoryLineRecord,
			@NonNull final Collection<I_M_InventoryLine_HU> inventoryLineHURecords)
	{
		final AttributeSetInstanceId asiId = AttributeSetInstanceId.ofRepoIdOrNone(inventoryLineRecord.getM_AttributeSetInstance_ID());
		final AttributesKey storageAttributesKey = AttributesKeys.createAttributesKeyFromASIStorageAttributes(asiId).orElse(AttributesKey.NONE);

		final LocatorId locatorId = warehousesRepo.getLocatorIdByRepoIdOrNull(inventoryLineRecord.getM_Locator_ID());

		final InventoryLineBuilder lineBuilder = InventoryLine.builder()
				.id(extractInventoryLineIdOrNull(inventoryLineRecord))
				.orgId(OrgId.ofRepoId(inventoryLineRecord.getAD_Org_ID()))
				.locatorId(locatorId)
				.productId(ProductId.ofRepoId(inventoryLineRecord.getM_Product_ID()))
				.asiId(asiId)
				.storageAttributesKey(storageAttributesKey);

		final HUAggregationType huAggregationType = HUAggregationType.ofNullableCode(inventoryLineRecord.getHUAggregationType());
		lineBuilder.huAggregationType(huAggregationType);

		if (HUAggregationType.SINGLE_HU.equals(huAggregationType))
		{
			final InventoryLineHU inventoryLineHU = toInventoryLineHU(inventoryLineRecord);
			lineBuilder.inventoryLineHU(inventoryLineHU);
		}
		// multiple HUs per line
		else
		{
			if (inventoryLineHURecords.isEmpty())
			{
				final InventoryLineHU inventoryLineHU = toInventoryLineHU(inventoryLineRecord);
				lineBuilder.inventoryLineHU(inventoryLineHU);
			}
			else
			{
				final UOMConversionContext uomConversionCtx = UOMConversionContext.of(inventoryLineRecord.getM_Product_ID());
				final UomId targetUomId = UomId.ofRepoId(inventoryLineRecord.getC_UOM_ID());

				for (final I_M_InventoryLine_HU inventoryLineHURecord : inventoryLineHURecords)
				{
					final InventoryLineHU inventoryLineHU = toInventoryLineHU(inventoryLineHURecord, uomConversionCtx, targetUomId);
					lineBuilder.inventoryLineHU(inventoryLineHU);
				}
			}
		}

		return lineBuilder.build();
	}

	private InventoryLineHU toInventoryLineHU(@NonNull final I_M_InventoryLine inventoryLineRecord)
	{
		final I_C_UOM uom = uomsRepo.getById(inventoryLineRecord.getC_UOM_ID());

		final Quantity qtyInternalUse;
		final Quantity qtyBook;
		final Quantity qtyCount;

		final BigDecimal qtyInternalUseBD = inventoryLineRecord.getQtyInternalUse();
		if (qtyInternalUseBD != null && qtyInternalUseBD.signum() != 0)
		{
			qtyInternalUse = Quantity.of(qtyInternalUseBD, uom);
			qtyBook = null;
			qtyCount = null;
		}
		else
		{
			qtyInternalUse = null;
			qtyBook = Quantity.of(inventoryLineRecord.getQtyBook(), uom);
			qtyCount = Quantity.of(inventoryLineRecord.getQtyCount(), uom);
		}

		return InventoryLineHU.builder()
				.huId(HuId.ofRepoIdOrNull(inventoryLineRecord.getM_HU_ID()))
				.qtyInternalUse(qtyInternalUse)
				.qtyBook(qtyBook)
				.qtyCount(qtyCount)
				.build();
	}

	private InventoryLineHU toInventoryLineHU(
			@NonNull final I_M_InventoryLine_HU inventoryLineHURecord,
			@NonNull final UOMConversionContext uomConversionCtx,
			@NonNull final UomId targetUomId)
	{
		final I_C_UOM uom = uomsRepo.getById(inventoryLineHURecord.getC_UOM_ID());
		final Quantity qtyInternalUseConv;
		final Quantity qtyBookConv;
		final Quantity qtyCountConv;

		final BigDecimal qtyInternalUseBD = inventoryLineHURecord.getQtyInternalUse();
		if (qtyInternalUseBD != null && qtyInternalUseBD.signum() != 0)
		{
			final Quantity qtyInternalUse = Quantity.of(qtyInternalUseBD, uom);

			qtyInternalUseConv = convBL.convertQuantityTo(qtyInternalUse, uomConversionCtx, targetUomId);
			qtyBookConv = null;
			qtyCountConv = null;
		}
		else
		{
			final Quantity qtyBook = Quantity.of(inventoryLineHURecord.getQtyBook(), uom);
			final Quantity qtyCount = Quantity.of(inventoryLineHURecord.getQtyCount(), uom);

			qtyInternalUseConv = null;
			qtyBookConv = convBL.convertQuantityTo(qtyBook, uomConversionCtx, targetUomId);
			qtyCountConv = convBL.convertQuantityTo(qtyCount, uomConversionCtx, targetUomId);
		}

		return InventoryLineHU.builder()
				.id(extractInventoryLineHUId(inventoryLineHURecord))
				.qtyInternalUse(qtyInternalUseConv)
				.qtyBook(qtyBookConv)
				.qtyCount(qtyCountConv)
				.huId(HuId.ofRepoIdOrNull(inventoryLineHURecord.getM_HU_ID()))
				.build();
	}

	public void save(@NonNull final Inventory inventory)
	{
		saveInventoryLines(inventory);
	}

	void saveInventoryLines(@NonNull final Inventory inventory)
	{
		final InventoryId inventoryId = inventory.getId();
		saveInventoryLines(inventory.getLines(), inventoryId);
	}

	private void saveInventoryLines(
			@NonNull final List<InventoryLine> lines,
			@NonNull final InventoryId inventoryId)
	{
		final ImmutableSet<InventoryLineId> inventoryLineIds = extractInventoryLineIds(lines);
		final HashMap<InventoryLineId, I_M_InventoryLine> existingInventoryLineRecords = getInventoryLineRecordsByIds(inventoryLineIds);

		for (final InventoryLine inventoryLine : lines)
		{
			final I_M_InventoryLine existingLineRecord = existingInventoryLineRecords.remove(inventoryLine.getId());
			saveInventoryLine(inventoryLine, inventoryId, existingLineRecord);
		}

		InterfaceWrapperHelper.deleteAll(existingInventoryLineRecords.values());
	}

	private static ImmutableSet<InventoryLineId> extractInventoryLineIds(final List<InventoryLine> lines)
	{
		return lines.stream()
				.map(InventoryLine::getId)
				.filter(Predicates.notNull())
				.collect(ImmutableSet.toImmutableSet());
	}

	public void saveInventoryLine(
			@NonNull final InventoryLine inventoryLine,
			@NonNull final InventoryId inventoryId)
	{
		final I_M_InventoryLine existingLineRecord = inventoryLine.getId() != null
				? getInventoryLineRecordById(inventoryLine.getId())
				: null;

		saveInventoryLine(inventoryLine, inventoryId, existingLineRecord);
	}

	private void saveInventoryLine(
			@NonNull final InventoryLine inventoryLine,
			@NonNull final InventoryId inventoryId,
			@Nullable final I_M_InventoryLine existingLineRecord)
	{
		final I_M_InventoryLine lineRecord;
		if (existingLineRecord != null)
		{
			lineRecord = existingLineRecord;
		}
		else
		{
			lineRecord = newInstance(I_M_InventoryLine.class);
		}

		lineRecord.setAD_Org_ID(inventoryLine.getOrgId().getRepoId());
		lineRecord.setM_Inventory_ID(inventoryId.getRepoId());

		final AttributesKey storageAttributesKey = inventoryLine.getStorageAttributesKey();

		final AttributeSetInstanceId asiId = coalesceSuppliers(
				() -> inventoryLine.getAsiId(),
				() -> AttributesKeys.createAttributeSetInstanceFromAttributesKey(storageAttributesKey));
		lineRecord.setM_AttributeSetInstance_ID(asiId.getRepoId());

		lineRecord.setM_Locator_ID(inventoryLine.getLocatorId().getRepoId());
		lineRecord.setM_Product_ID(inventoryLine.getProductId().getRepoId());

		final HUAggregationType huAggregationType = inventoryLine.getHuAggregationType();
		lineRecord.setHUAggregationType(HUAggregationType.toCodeOrNull(huAggregationType));

		final HuId huId = inventoryLine.isSingleHUAggregation()
				? inventoryLine.getSingleLineHU().getHuId()
				: null;
		lineRecord.setM_HU_ID(HuId.toRepoId(huId));
		// lineRecord.setM_HU_PI_Item_Product(null); // TODO
		// lineRecord.setQtyTU(BigDecimal.ZERO); // TODO

		updateInventoryLineRecordQuantities(lineRecord, inventoryLine);

		saveRecord(lineRecord);
		inventoryLine.setId(extractInventoryLineId(lineRecord));

		saveInventoryLineHURecords(inventoryLine);
	}

	private void updateInventoryLineRecordQuantities(
			@NonNull final I_M_InventoryLine lineRecord,
			@NonNull final InventoryLine from)
	{
		final UomId uomId;
		final BigDecimal qtyInternalUseBD;
		final BigDecimal qtyBookBD;
		final BigDecimal qtyCountBD;

		if (from.getInventoryType().isInternalUse())
		{
			final Quantity qtyInternalUse = from.getQtyInternalUse();

			uomId = qtyInternalUse.getUomId();
			qtyInternalUseBD = qtyInternalUse.getAsBigDecimal();
			qtyBookBD = BigDecimal.ZERO; // using ZERO instead of null because the column is mandatory in DB
			qtyCountBD = BigDecimal.ZERO; // using ZERO instead of null because the column is mandatory in DB
		}
		else
		{
			final Quantity qtyBook = from.getQtyBook();
			final Quantity qtyCount = from.getQtyCount();

			uomId = Quantity.getCommonUomIdOfAll(qtyCount, qtyBook);
			qtyInternalUseBD = null;
			qtyBookBD = qtyBook.getAsBigDecimal();
			qtyCountBD = qtyCount.getAsBigDecimal();
		}

		lineRecord.setQtyInternalUse(qtyInternalUseBD);
		lineRecord.setQtyBook(qtyBookBD);
		lineRecord.setQtyCount(qtyCountBD);
		lineRecord.setC_UOM_ID(uomId.getRepoId());
	}

	public void saveInventoryLineHURecords(@NonNull final InventoryLine inventoryLine)
	{
		if (inventoryLine.isSingleHUAggregation())
		{
			if (inventoryLine.getId() != null)
			{
				deleteInventoryLineHUs(inventoryLine.getId());
			}

			return;
		}
		else
		{
			final HashMap<InventoryLineHUId, I_M_InventoryLine_HU> existingInventoryLineHURecords = retrieveInventoryLineHURecords(inventoryLine.getId())
					.stream()
					.collect(GuavaCollectors.toHashMapByKey(l -> extractInventoryLineHUId(l)));

			for (final InventoryLineHU lineHU : inventoryLine.getInventoryLineHUs())
			{
				I_M_InventoryLine_HU lineHURecord = existingInventoryLineHURecords.remove(lineHU.getId());
				if (lineHURecord == null)
				{
					lineHURecord = newInstance(I_M_InventoryLine_HU.class);
				}

				lineHURecord.setAD_Org_ID(inventoryLine.getId().getRepoId());
				lineHURecord.setM_InventoryLine_ID(inventoryLine.getId().getRepoId());
				updateInventoryLineHURecord(lineHURecord, lineHU);

				saveRecord(lineHURecord);
				lineHU.setId(extractInventoryLineHUId(lineHURecord));
			}

			// the pre-existing records that we did not see until now are not needed anymore; delete them.
			InterfaceWrapperHelper.deleteAll(existingInventoryLineHURecords.values());
		}
	}

	private static void updateInventoryLineHURecord(
			@NonNull I_M_InventoryLine_HU record,
			@NonNull final InventoryLineHU fromLineHU)
	{
		// record.setAD_Org_ID(orgId.getRepoId());
		// record.setM_InventoryLine_ID(lineId.getRepoId());

		record.setM_HU_ID(HuId.toRepoId(fromLineHU.getHuId()));

		updateInventoryLineHURecordQuantities(record, fromLineHU);
	}

	private static void updateInventoryLineHURecordQuantities(
			@NonNull final I_M_InventoryLine_HU lineRecord,
			@NonNull final InventoryLineHU from)
	{
		final UomId uomId;
		final BigDecimal qtyInternalUseBD;
		final BigDecimal qtyBookBD;
		final BigDecimal qtyCountBD;

		if (from.getInventoryType().isInternalUse())
		{
			final Quantity qtyInternalUse = from.getQtyInternalUse();

			uomId = qtyInternalUse.getUomId();
			qtyInternalUseBD = qtyInternalUse.getAsBigDecimal();
			qtyBookBD = BigDecimal.ZERO; // using ZERO instead of null because the column is mandatory in DB
			qtyCountBD = BigDecimal.ZERO; // using ZERO instead of null because the column is mandatory in DB
		}
		else
		{
			final Quantity qtyBook = from.getQtyBook();
			final Quantity qtyCount = from.getQtyCount();

			uomId = Quantity.getCommonUomIdOfAll(qtyCount, qtyBook);
			qtyInternalUseBD = null;
			qtyBookBD = qtyBook.getAsBigDecimal();
			qtyCountBD = qtyCount.getAsBigDecimal();
		}

		lineRecord.setQtyInternalUse(qtyInternalUseBD);
		lineRecord.setQtyBook(qtyBookBD);
		lineRecord.setQtyCount(qtyCountBD);
		lineRecord.setC_UOM_ID(uomId.getRepoId());
	}

	private static InventoryLineHUId extractInventoryLineHUId(@NonNull final I_M_InventoryLine_HU inventoryLineHURecord)
	{
		return InventoryLineHUId.ofRepoId(inventoryLineHURecord.getM_InventoryLine_HU_ID());
	}

	private List<I_M_InventoryLine_HU> retrieveInventoryLineHURecords(@NonNull final InventoryLineId inventoryLineId)
	{
		return retrieveInventoryLineHURecords(ImmutableSet.of(inventoryLineId)).get(inventoryLineId);
	}

	private ImmutableListMultimap<InventoryLineId, I_M_InventoryLine_HU> retrieveInventoryLineHURecords(@NonNull final Collection<InventoryLineId> inventoryLineIds)
	{
		if (inventoryLineIds.isEmpty())
		{
			return ImmutableListMultimap.of();
		}

		return Services
				.get(IQueryBL.class)
				.createQueryBuilder(I_M_InventoryLine_HU.class)
				.addOnlyActiveRecordsFilter()
				.addInArrayFilter(I_M_InventoryLine_HU.COLUMNNAME_M_InventoryLine_ID, inventoryLineIds)
				.orderBy(I_M_InventoryLine_HU.COLUMN_M_HU_ID)
				.create()
				.stream()
				.collect(ImmutableListMultimap.toImmutableListMultimap(
						record -> InventoryLineId.ofRepoId(record.getM_InventoryLine_ID()),
						record -> record));
	}

	public void deleteInventoryLineHUs(@NonNull final InventoryLineId inventoryLineId)
	{
		queryBL.createQueryBuilder(I_M_InventoryLine_HU.class)
				.addEqualsFilter(I_M_InventoryLine_HU.COLUMN_M_InventoryLine_ID, inventoryLineId)
				.create()
				.delete();
	}

	private List<I_M_InventoryLine> retrieveLineRecords(@NonNull final InventoryId inventoryId)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_M_InventoryLine.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_M_InventoryLine.COLUMNNAME_M_Inventory_ID, inventoryId)
				.orderBy(I_M_InventoryLine.COLUMNNAME_Line)
				.orderBy(I_M_InventoryLine.COLUMNNAME_M_InventoryLine_ID)
				.create()
				.list();
	}
}

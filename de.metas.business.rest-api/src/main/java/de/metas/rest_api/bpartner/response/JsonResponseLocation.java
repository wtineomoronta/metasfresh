package de.metas.rest_api.bpartner.response;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import de.metas.rest_api.JsonExternalId;
import de.metas.rest_api.MetasfreshId;
import de.metas.rest_api.changelog.JsonChangeInfo;
import io.swagger.annotations.ApiModelProperty;
import lombok.Builder;
import lombok.Value;

/*
 * #%L
 * de.metas.ordercandidate.rest-api
 * %%
 * Copyright (C) 2018 metas GmbH
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

@Value
public class JsonResponseLocation
{
	public static final String METASFRESH_ID = "metasfreshId";
	public static final String EXTERNAL_ID = "externalId";
	public static final String ADDRESS1 = "address1";
	public static final String ADDRESS2 = "address2";
	public static final String POSTAL = "postal";
	public static final String PO_BOX = "poBox";
	public static final String DISTRICT = "district";
	public static final String REGION = "region";
	public static final String CITY = "city";
	public static final String COUNTRY_CODE = "countryCode";
	public static final String GLN = "gln";

	@JsonInclude(Include.NON_NULL)
	@ApiModelProperty(dataType = "java.lang.Integer")
	private MetasfreshId metasfreshId;

	@ApiModelProperty(allowEmptyValue = true, //
			dataType = "java.lang.String", //
			value = "This translates to `C_BPartner_Location.ExternalId`.\n"
					+ "Needs to be unique over all business partners (not only the one this location belongs to).")
	private JsonExternalId externalId;

	private String address1;

	@JsonInclude(Include.NON_EMPTY)
	private String address2;

	@JsonInclude(Include.NON_EMPTY)
	private String poBox;

	private String postal;

	private String city;

	@JsonInclude(Include.NON_EMPTY)
	private String district;

	@JsonInclude(Include.NON_EMPTY)
	private String region;

	private String countryCode;

	@ApiModelProperty(allowEmptyValue = true, //
			value = "This translates to `C_BPartner_Location.GLN`.")
	private String gln;

	@JsonInclude(Include.NON_NULL)
	JsonChangeInfo changeInfo;

	@Builder(toBuilder = true)
	@JsonCreator
	private JsonResponseLocation(
			@JsonProperty(METASFRESH_ID) @Nullable final MetasfreshId metasfreshId,
			@JsonProperty(EXTERNAL_ID) @Nullable final JsonExternalId externalId,
			@JsonProperty(ADDRESS1) @Nullable final String address1,
			@JsonProperty(ADDRESS2) @Nullable final String address2,
			@JsonProperty(POSTAL) final String postal,
			@JsonProperty(PO_BOX) final String poBox,
			@JsonProperty(DISTRICT) final String district,
			@JsonProperty(REGION) final String region,
			@JsonProperty(CITY) final String city,
			@JsonProperty(COUNTRY_CODE) @Nullable final String countryCode,
			@JsonProperty(GLN) @Nullable final String gln,
			@JsonProperty("changeInfo") @Nullable JsonChangeInfo changeInfo)
	{
		this.metasfreshId = metasfreshId;
		this.gln = gln;
		this.externalId = externalId;
		this.address1 = address1;
		this.address2 = address2;
		this.postal = postal;
		this.poBox = poBox;
		this.district = district;
		this.region = region;
		this.city = city;
		this.countryCode = countryCode; // mandatory only if we want to insert/update a new location

		this.changeInfo = changeInfo;
	}
}

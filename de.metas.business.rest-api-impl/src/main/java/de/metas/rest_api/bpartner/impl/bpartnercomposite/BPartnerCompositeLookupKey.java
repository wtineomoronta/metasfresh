package de.metas.rest_api.bpartner.impl.bpartnercomposite;

import static de.metas.util.Check.assumeNotEmpty;

import de.metas.rest_api.JsonExternalId;
import de.metas.rest_api.MetasfreshId;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.business.rest-api-impl
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

@Value
public class BPartnerCompositeLookupKey
{
	public static BPartnerCompositeLookupKey ofMetasfreshId(@NonNull final MetasfreshId metasfreshId)
	{
		return new BPartnerCompositeLookupKey(metasfreshId, null, null, null);
	}

	public static BPartnerCompositeLookupKey ofJsonExternalId(@NonNull final JsonExternalId jsonExternalId)
	{
		return new BPartnerCompositeLookupKey(null, jsonExternalId, null, null);
	}

	public static BPartnerCompositeLookupKey ofCode(@NonNull final String code)
	{
		assumeNotEmpty(code, "Given parameter 'code' may not be empty");
		return new BPartnerCompositeLookupKey(null, null, code.trim(), null);
	}

	public static BPartnerCompositeLookupKey ofGln(@NonNull final String gln)
	{
		assumeNotEmpty(gln, "Given parameter 'gln' may not be empty");
		return new BPartnerCompositeLookupKey(null, null, null, gln);
	}

	MetasfreshId metasfreshId;

	JsonExternalId jsonExternalId;

	String code;

	String gln;

	private BPartnerCompositeLookupKey(
			MetasfreshId metasfreshId,
			JsonExternalId jsonExternalId,
			String code,
			String gln)
	{
		this.metasfreshId = metasfreshId;
		this.jsonExternalId = jsonExternalId;
		this.code = code;
		this.gln = gln;
	}


}

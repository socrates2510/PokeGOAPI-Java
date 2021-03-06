/*
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.pokegoapi.api.map;

import POGOProtos.Inventory.Item.ItemIdOuterClass.ItemId;
import POGOProtos.Map.Fort.FortDataOuterClass.FortData;
import POGOProtos.Map.Fort.FortTypeOuterClass.FortType;
import POGOProtos.Map.MapCellOuterClass.MapCell;
import POGOProtos.Map.Pokemon.MapPokemonOuterClass.MapPokemon;
import POGOProtos.Map.Pokemon.NearbyPokemonOuterClass;
import POGOProtos.Map.Pokemon.WildPokemonOuterClass;
import POGOProtos.Map.SpawnPointOuterClass;
import POGOProtos.Networking.Requests.Messages.CatchPokemonMessageOuterClass.CatchPokemonMessage;
import POGOProtos.Networking.Requests.Messages.EncounterMessageOuterClass;
import POGOProtos.Networking.Requests.Messages.FortDetailsMessageOuterClass.FortDetailsMessage;
import POGOProtos.Networking.Requests.Messages.FortSearchMessageOuterClass.FortSearchMessage;
import POGOProtos.Networking.Requests.Messages.GetMapObjectsMessageOuterClass;
import POGOProtos.Networking.Requests.Messages.GetMapObjectsMessageOuterClass.GetMapObjectsMessage;
import POGOProtos.Networking.Requests.RequestTypeOuterClass.RequestType;
import POGOProtos.Networking.Responses.CatchPokemonResponseOuterClass.CatchPokemonResponse;
import POGOProtos.Networking.Responses.EncounterResponseOuterClass.EncounterResponse;
import POGOProtos.Networking.Responses.FortDetailsResponseOuterClass;
import POGOProtos.Networking.Responses.FortSearchResponseOuterClass.FortSearchResponse;
import POGOProtos.Networking.Responses.GetMapObjectsResponseOuterClass.GetMapObjectsResponse;
import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.annimon.stream.function.Function;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.gym.Gym;
import com.pokegoapi.api.map.fort.FortDetails;
import com.pokegoapi.api.map.pokemon.CatchablePokemon;
import com.pokegoapi.api.map.pokemon.NearbyPokemon;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokegoapi.google.common.geometry.MutableInteger;
import com.pokegoapi.google.common.geometry.S2CellId;
import com.pokegoapi.google.common.geometry.S2LatLng;
import com.pokegoapi.main.AsyncServerRequest;
import com.pokegoapi.main.ServerRequest;
import com.pokegoapi.util.DummyFuture;
import com.pokegoapi.util.FutureWrapper;
import com.pokegoapi.util.PokemonFuture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class Map {
	private static int CELL_WIDTH = 3;
	// time between getting a new MapObjects
	private static int RESEND_REQUEST = 5000;
	private final PokemonGo api;
	private MapObjects cachedMapObjects;
	private long lastMapUpdate;

	/**
	 * Instantiates a new Map.
	 *
	 * @param api the api
	 * @throws LoginFailedException  if the login failed
	 * @throws RemoteServerException When a buffer exception is thrown
	 */
	public Map(PokemonGo api) throws LoginFailedException, RemoteServerException {
		this.api = api;
		cachedMapObjects = new MapObjects(api);
		lastMapUpdate = 0;
	}


	/**
	 * Returns a list of catchable pokemon around the current location.
	 *
	 * @return a List of CatchablePokemon at your current location
	 */
	public PokemonFuture<List<CatchablePokemon>> getCatchablePokemonAsync() {
		List<Long> cellIds = getDefaultCells();
		return new FutureWrapper<MapObjects, List<CatchablePokemon>>(getMapObjectsAsync(cellIds)) {
			@Override
			protected List<CatchablePokemon> handle(MapObjects mapObjects) throws RemoteServerException {
				Set<CatchablePokemon> catchablePokemons = new HashSet<>();
				for (MapPokemon mapPokemon : mapObjects.getCatchablePokemons()) {
					catchablePokemons.add(new CatchablePokemon(api, mapPokemon));
				}

				for (WildPokemonOuterClass.WildPokemon wildPokemon : mapObjects.getWildPokemons()) {
					catchablePokemons.add(new CatchablePokemon(api, wildPokemon));
				}
				// TODO: Check if this code is correct; merged because this contains many other fixes
				/*for (Pokestop pokestop : objects.getPokestops()) {
					if (pokestop.inRange() && pokestop.hasLurePokemon()) {
						catchablePokemons.add(new CatchablePokemon(api, pokestop.getFortData()));
					}
				}*/
				return new ArrayList<>(catchablePokemons);
			}
		};
	}

	/**
	 * Returns a list of catchable pokemon around the current location.
	 *
	 * @return a List of CatchablePokemon at your current location
	 */
	public List<CatchablePokemon> getCatchablePokemon() throws LoginFailedException, RemoteServerException {
		return getCatchablePokemonAsync().toBlocking();
	}

	/**
	 * Returns a list of nearby pokemon (non-catchable).
	 *
	 * @return a List of NearbyPokemon at your current location
	 * @throws LoginFailedException  if the login failed
	 * @throws RemoteServerException When a buffer exception is thrown
	 */
	public PokemonFuture<List<NearbyPokemon>> getNearbyPokemonAsync() {
		return new FutureWrapper<MapObjects, List<NearbyPokemon>>(getMapObjectsAsync(getDefaultCells())) {
			@Override
			protected List<NearbyPokemon> handle(MapObjects result) throws RemoteServerException {
				List<NearbyPokemon> pokemons = new ArrayList<>();
				for (NearbyPokemonOuterClass.NearbyPokemon pokemon : result.getNearbyPokemons()) {
					pokemons.add(new NearbyPokemon(pokemon));
				}

				return pokemons;
			}
		};
	}

	/**
	 * Returns a list of nearby pokemon (non-catchable).
	 *
	 * @return a List of NearbyPokemon at your current location
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	public List<NearbyPokemon> getNearbyPokemon() throws LoginFailedException, RemoteServerException {
		return getNearbyPokemonAsync().toBlocking();
	}

	/**
	 * Returns a list of spawn points.
	 *
	 * @return list of spawn points
	 */
	public PokemonFuture<List<Point>> getSpawnPointsAsync() {
		return new FutureWrapper<MapObjects, List<Point>>(getMapObjectsAsync(getDefaultCells())) {
			@Override
			protected List<Point> handle(MapObjects result) throws RemoteServerException {
				List<Point> points = new ArrayList<>();

				for (SpawnPointOuterClass.SpawnPoint point : result.getSpawnPoints()) {
					points.add(new Point(point));
				}

				return points;
			}
		};
	}

	/**
	 * Returns a list of spawn points.
	 *
	 * @return list of spawn points
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	public List<Point> getSpawnPoints() throws LoginFailedException, RemoteServerException {
		return getSpawnPointsAsync().toBlocking();
	}

	/**
	 * Get a list of gyms near the current location.
	 *
	 * @return List of gyms
	 */
	public PokemonFuture<List<Gym>> getGymsAsync() {
		return new FutureWrapper<MapObjects, List<Gym>>(getMapObjectsAsync(getDefaultCells())) {
			@Override
			protected List<Gym> handle(MapObjects result) throws RemoteServerException {
				List<Gym> gyms = new ArrayList<>();

				for (FortData fortdata : result.getGyms()) {
					gyms.add(new Gym(api, fortdata));
				}

				return gyms;
			}
		};
	}

	/**
	 * Get a list of gyms near the current location.
	 *
	 * @return List of gyms
	 */
	public List<Gym> getGyms() throws LoginFailedException, RemoteServerException {
		return getGymsAsync().toBlocking();
	}

	/**
	 * Returns a list of decimated spawn points at current location.
	 *
	 * @return list of spawn points
	 */
	public PokemonFuture<List<Point>> getDecimatedSpawnPointsAsync() {
		return new FutureWrapper<MapObjects, List<Point>>(getMapObjectsAsync(getDefaultCells())) {
			@Override
			protected List<Point> handle(MapObjects result) throws RemoteServerException {
				List<Point> points = new ArrayList<>();
				for (SpawnPointOuterClass.SpawnPoint point : result.getDecimatedSpawnPoints()) {
					points.add(new Point(point));
				}

				return points;
			}
		};
	}

	/**
	 * Returns a list of decimated spawn points at current location.
	 *
	 * @return list of spawn points
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	public List<Point> getDecimatedSpawnPoints() throws LoginFailedException, RemoteServerException {
		return getDecimatedSpawnPointsAsync().toBlocking();
	}

	/**
	 * Returns MapObjects around your current location.
	 *
	 * @return MapObjects at your current location
	 */
	public PokemonFuture<MapObjects> getMapObjectsAsync() {
		return getMapObjectsAsync(getDefaultCells());
	}

	/**
	 * Returns MapObjects around your current location within a given width.
	 *
	 * @param width width
	 * @return MapObjects at your current location
	 */
	public PokemonFuture<MapObjects> getMapObjectsAsync(int width) {
		return getMapObjectsAsync(getCellIds(api.getLatitude(), api.getLongitude(), width));
	}

	/**
	 * Returns the cells requested.
	 *
	 * @param cellIds List of cellId
	 * @return MapObjects in the given cells
	 */
	public PokemonFuture<MapObjects> getMapObjectsAsync(List<Long> cellIds) {

		if ( (api.currentTimeMillis() - lastMapUpdate) < RESEND_REQUEST ) {
			return new DummyFuture<MapObjects>(cachedMapObjects);
		}

		lastMapUpdate = api.currentTimeMillis();
		GetMapObjectsMessage.Builder builder = GetMapObjectsMessageOuterClass.GetMapObjectsMessage.newBuilder()
				.setLatitude(api.getLatitude())
				.setLongitude(api.getLongitude());

		int index = 0;
		for (Long cellId : cellIds) {
			builder.addCellId(cellId);
			long time = 0;
			builder.addSinceTimestampMs(0);
			index++;

		}
		final AsyncServerRequest asyncServerRequest = new AsyncServerRequest(
				RequestType.GET_MAP_OBJECTS, builder.build());
		return new FutureWrapper<ByteString, MapObjects>(api.getRequestHandler()
				.sendAsyncServerRequests(asyncServerRequest)) {
			@Override
			protected MapObjects handle(ByteString byteString) throws RemoteServerException {
				GetMapObjectsResponse response;
				try {
					response = GetMapObjectsResponse.parseFrom(byteString);
				} catch (InvalidProtocolBufferException e) {
					throw new RemoteServerException(e);
				}

				MapObjects result = new MapObjects(api);
				cachedMapObjects = result;
				for (MapCell mapCell : response.getMapCellsList()) {
					result.addNearbyPokemons(mapCell.getNearbyPokemonsList());
					result.addCatchablePokemons(mapCell.getCatchablePokemonsList());
					result.addWildPokemons(mapCell.getWildPokemonsList());
					result.addDecimatedSpawnPoints(mapCell.getDecimatedSpawnPointsList());
					result.addSpawnPoints(mapCell.getSpawnPointsList());

					java.util.Map<FortType, List<FortData>> groupedForts = Stream.of(mapCell.getFortsList())
							.collect(Collectors.groupingBy(new Function<FortData, FortType>() {
								@Override
								public FortType apply(FortData fortData) {
									return fortData.getType();
								}
							}));
					result.addGyms(groupedForts.get(FortType.GYM));
					result.addPokestops(groupedForts.get(FortType.CHECKPOINT));
				}



				return result;
			}
		};
	}

	/**
	 * Returns MapObjects around your current location.
	 *
	 * @return MapObjects at your current location
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	public MapObjects getMapObjects() throws LoginFailedException, RemoteServerException {
		return getMapObjectsAsync().toBlocking();
	}

	/**
	 * Returns MapObjects around your current location within a given width.
	 *
	 * @param width width
	 * @return MapObjects at your current location
     *
     * @throws LoginFailedException If login fails.
     * @throws RemoteServerException If request errors occurred.
	 */
	public MapObjects getMapObjects(int width) throws LoginFailedException, RemoteServerException {
		return getMapObjectsAsync(width).toBlocking();
	}

	/**
	 * Returns 5x5 cells with the requested lattitude/longitude in the center cell.
	 *
	 * @param latitude  latitude
	 * @param longitude longitude
	 * @return MapObjects in the given cells
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	@Deprecated
	public MapObjects getMapObjects(double latitude, double longitude)
			throws LoginFailedException, RemoteServerException {
		return getMapObjects(latitude, longitude, CELL_WIDTH);
	}

	/**
	 * Returns the cells requested, you should send a latitude/longitude to fake a near location.
	 *
	 * @param cellIds   List of cellIds
	 * @param latitude  latitude
	 * @param longitude longitude
	 * @return MapObjects in the given cells
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	@Deprecated
	public MapObjects getMapObjects(List<Long> cellIds, double latitude, double longitude)
			throws LoginFailedException, RemoteServerException {
		return getMapObjects(cellIds, latitude, longitude, 0);
	}

	/**
	 * Returns `width` * `width` cells with the requested latitude/longitude in the center.
	 *
	 * @param latitude  latitude
	 * @param longitude longitude
	 * @param width     width
	 * @return MapObjects in the given cells
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	@Deprecated
	public MapObjects getMapObjects(double latitude, double longitude, int width)
			throws LoginFailedException, RemoteServerException {
		return getMapObjects(getCellIds(latitude, longitude, width), latitude, longitude);
	}

	/**
	 * Returns the cells requested.
	 *
	 * @param cellIds   cellIds
	 * @param latitude  latitude
	 * @param longitude longitude
     * @param altitude altitude
	 * @return MapObjects in the given cells
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	@Deprecated
	public MapObjects getMapObjects(List<Long> cellIds, double latitude, double longitude, double altitude)
			throws LoginFailedException, RemoteServerException {
		api.setLatitude(latitude);
		api.setLongitude(longitude);
		api.setAltitude(altitude);
		return getMapObjects(cellIds);
	}

	/**
	 * Returns the cells requested.
	 *
	 * @param cellIds List of cellId
	 * @return MapObjects in the given cells
     * @throws LoginFailedException  if the login failed
     * @throws RemoteServerException When a buffer exception is thrown
	 */
	public MapObjects getMapObjects(List<Long> cellIds) throws LoginFailedException, RemoteServerException {
		return getMapObjectsAsync(cellIds).toBlocking();
	}

	/**
	 * Get a list of all the Cell Ids.
	 *
	 * @param latitude  latitude
	 * @param longitude longitude
	 * @param width     width
	 * @return List of Cells
	 */
	public List<Long> getCellIds(double latitude, double longitude, int width) {
		S2LatLng latLng = S2LatLng.fromDegrees(latitude, longitude);
		S2CellId cellId = S2CellId.fromLatLng(latLng).parent(15);

		MutableInteger index = new MutableInteger(0);
		MutableInteger jindex = new MutableInteger(0);


		int level = cellId.level();
		int size = 1 << (S2CellId.MAX_LEVEL - level);
		int face = cellId.toFaceIJOrientation(index, jindex, null);

		List<Long> cells = new ArrayList<Long>();

		int halfWidth = (int) Math.floor(width / 2);
		for (int x = -halfWidth; x <= halfWidth; x++) {
			for (int y = -halfWidth; y <= halfWidth; y++) {
				cells.add(S2CellId.fromFaceIJ(face, index.intValue() + x * size, jindex.intValue() + y * size)
						.parent(15).id());
			}
		}
		return cells;
	}

	/**
	 * Gets fort details.
	 *
	 * @param id  the id
	 * @param lon the lon
	 * @param lat the lat
	 * @return the fort details
	 */
	public PokemonFuture<FortDetails> getFortDetailsAsync(String id, double lon, double lat) {
		FortDetailsMessage reqMsg = FortDetailsMessage.newBuilder()
				.setFortId(id)
				.setLatitude(lat)
				.setLongitude(lon)
				.build();

		AsyncServerRequest serverRequest = new AsyncServerRequest(RequestType.FORT_DETAILS,
				reqMsg);
		return new FutureWrapper<ByteString, FortDetails>(api.getRequestHandler()
				.sendAsyncServerRequests(serverRequest)) {
			@Override
			protected FortDetails handle(ByteString byteString) throws RemoteServerException {
				FortDetailsResponseOuterClass.FortDetailsResponse response;
				try {
					response = FortDetailsResponseOuterClass.FortDetailsResponse.parseFrom(byteString);
				} catch (InvalidProtocolBufferException e) {
					throw new RemoteServerException(e);
				}
				return new FortDetails(response);
			}
		};
	}

	/**
	 * Gets fort details.
	 *
	 * @param id  the id
	 * @param lon the lon
	 * @param lat the lat
	 * @return the fort details
	 * @throws LoginFailedException  the login failed exception
	 * @throws RemoteServerException the remote server exception
	 */
	public FortDetails getFortDetails(String id, double lon, double lat)
			throws LoginFailedException, RemoteServerException {
		return getFortDetailsAsync(id, lon, lat).toBlocking();
	}

	/**
	 * Search fort fort search response.
	 *
	 * @param fortData the fort data
	 * @return the fort search response
	 * @throws LoginFailedException  the login failed exception
	 * @throws RemoteServerException the remote server exception
	 */
	@Deprecated
	public FortSearchResponse searchFort(FortData fortData) throws LoginFailedException, RemoteServerException {
		FortSearchMessage reqMsg = FortSearchMessage.newBuilder()
				.setFortId(fortData.getId())
				.setFortLatitude(fortData.getLatitude())
				.setFortLongitude(fortData.getLongitude())
				.setPlayerLatitude(api.getLatitude())
				.setPlayerLongitude(api.getLongitude())
				.build();
		ServerRequest serverRequest = new ServerRequest(RequestType.FORT_SEARCH, reqMsg);

		api.getRequestHandler().sendServerRequests(serverRequest);

		FortSearchResponse response;
		try {
			response = FortSearchResponse.parseFrom(serverRequest.getData());
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
		return response;
	}

	/**
	 * Encounter pokemon encounter response.
	 *
	 * @param catchablePokemon the catchable pokemon
	 * @return the encounter response
	 * @throws LoginFailedException  the login failed exception
	 * @throws RemoteServerException the remote server exception
	 */
	@Deprecated
	public EncounterResponse encounterPokemon(MapPokemon catchablePokemon)
			throws LoginFailedException, RemoteServerException {

		EncounterMessageOuterClass.EncounterMessage reqMsg = EncounterMessageOuterClass.EncounterMessage.newBuilder()
				.setEncounterId(catchablePokemon.getEncounterId())
				.setPlayerLatitude(api.getLatitude())
				.setPlayerLongitude(api.getLongitude())
				.setSpawnPointId(catchablePokemon.getSpawnPointId())
				.build();
		ServerRequest serverRequest = new ServerRequest(RequestType.ENCOUNTER, reqMsg);
		api.getRequestHandler().sendServerRequests(serverRequest);

		EncounterResponse response;
		try {
			response = EncounterResponse.parseFrom(serverRequest.getData());
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
		return response;
	}

	/**
	 * Catch pokemon catch pokemon response.
	 *
	 * @param catchablePokemon      the catchable pokemon
	 * @param normalizedHitPosition the normalized hit position
	 * @param normalizedReticleSize the normalized reticle size
	 * @param spinModifier          the spin modifier
	 * @param pokeball              the pokeball
	 * @return the catch pokemon response
	 * @throws LoginFailedException  the login failed exception
	 * @throws RemoteServerException the remote server exception
	 */
	@Deprecated
	public CatchPokemonResponse catchPokemon(
			MapPokemon catchablePokemon,
			double normalizedHitPosition,
			double normalizedReticleSize,
			double spinModifier,
			ItemId pokeball)
			throws LoginFailedException, RemoteServerException {

		CatchPokemonMessage reqMsg = CatchPokemonMessage.newBuilder()
				.setEncounterId(catchablePokemon.getEncounterId())
				.setHitPokemon(true)
				.setNormalizedHitPosition(normalizedHitPosition)
				.setNormalizedReticleSize(normalizedReticleSize)
				.setSpawnPointId(catchablePokemon.getSpawnPointId())
				.setSpinModifier(spinModifier)
				.setPokeball(pokeball)
				.build();
		ServerRequest serverRequest = new ServerRequest(RequestType.CATCH_POKEMON, reqMsg);
		api.getRequestHandler().sendServerRequests(serverRequest);

		CatchPokemonResponse response;
		try {
			response = CatchPokemonResponse.parseFrom(serverRequest.getData());
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
		return response;
	}


	private List<Long> getDefaultCells() {
		return getCellIds(api.getLatitude(), api.getLongitude(), CELL_WIDTH);
	}

}

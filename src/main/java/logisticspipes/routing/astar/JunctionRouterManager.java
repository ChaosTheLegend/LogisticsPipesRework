package logisticspipes.routing.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.RouterManager;

/**
 * Router manager that creates {@link JunctionRouter}s on the server. Client routers, direct connections and security
 * stations are inherited unchanged from {@link RouterManager}.
 */
public class JunctionRouterManager extends RouterManager {

    private final ArrayList<IRouter> routersServer = new ArrayList<>();
    private final Map<UUID, Integer> uuidMap = new HashMap<>();

    @Override
    public IRouter getRouter(int id) {
        if (id <= 0 || MainProxy.isClient()) {
            return null;
        }
        return getServerRouter(id);
    }

    private IRouter getServerRouter(int id) {
        synchronized (routersServer) {
            return id < routersServer.size() ? routersServer.get(id) : null;
        }
    }

    @Override
    public IRouter getRouterUnsafe(Integer id, boolean side) {
        if (side || id <= 0) {
            return null;
        }
        return getServerRouter(id);
    }

    @Override
    public int getIDforUUID(UUID id) {
        if (id == null) {
            return -1;
        }
        synchronized (routersServer) {
            Integer iId = uuidMap.get(id);
            return iId == null ? -1 : iId;
        }
    }

    @Override
    public void removeRouter(int id) {
        if (!MainProxy.isClient()) {
            synchronized (routersServer) {
                if (id >= 0 && id < routersServer.size()) {
                    routersServer.set(id, null);
                }
            }
        }
    }

    @Override
    public IRouter getOrCreateRouter(UUID uuid, int dimension, int xCoord, int yCoord, int zCoord,
            boolean forceCreateDuplicate) {
        if (MainProxy.isClient()) {
            return super.getOrCreateRouter(uuid, dimension, xCoord, yCoord, zCoord, forceCreateDuplicate);
        }
        synchronized (routersServer) {
            if (!forceCreateDuplicate) {
                for (IRouter r : routersServer) {
                    if (r != null && r.isAt(dimension, xCoord, yCoord, zCoord)) {
                        return r;
                    }
                }
            }
            IRouter r = new JunctionRouter(uuid, dimension, xCoord, yCoord, zCoord);
            int rId = r.getSimpleID();
            if (routersServer.size() <= rId) {
                routersServer.ensureCapacity(rId + 1);
                while (routersServer.size() <= rId) {
                    routersServer.add(null);
                }
            }
            routersServer.set(rId, r);
            uuidMap.put(r.getId(), r.getSimpleID());
            return r;
        }
    }

    @Override
    public boolean isRouter(int id) {
        if (MainProxy.isClient()) {
            return true;
        }
        return getServerRouter(id) != null;
    }

    @Override
    public boolean isRouterUnsafe(int id, boolean side) {
        if (side) {
            return true;
        }
        return getServerRouter(id) != null;
    }

    @Override
    public List<IRouter> getRouters() {
        if (MainProxy.isClient()) {
            return super.getRouters();
        }
        return Collections.unmodifiableList(routersServer);
    }

    @Override
    public void serverStopClean() {
        super.serverStopClean();
        synchronized (routersServer) {
            routersServer.clear();
            uuidMap.clear();
        }
    }

    @Override
    public void dimensionUnloaded(int dim) {
        synchronized (routersServer) {
            for (IRouter r : routersServer) {
                if (r != null && r.isInDim(dim)) {
                    r.clearPipeCache();
                    r.clearInterests();
                }
            }
        }
    }

    @Override
    public void printAllRouters() {
        synchronized (routersServer) {
            for (IRouter router : routersServer) {
                if (router != null) {
                    System.out.println(router);
                }
            }
        }
        for (String line : LPJunctionNetwork.describe()) {
            System.out.println(line);
        }
    }
}

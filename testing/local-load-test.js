import grpc from 'k6/net/grpc';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { htmlReport } from "https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js";

const client = new grpc.Client();
client.load(['.'], 'route.proto');

const errorRate = new Rate('grpc_error_rate');

export const options = {
  scenarios: {
    local_routing_stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 100 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 0 }, 
      ],
    },
  },
  thresholds: {
    'grpc_req_duration{method:SnapToNode}': ['p(99) < 15'],             
    'grpc_req_duration{method:GetRoute}': ['p(95) < 30', 'p(99) < 50'],
    'grpc_req_duration{method:GetBoundaryConnections}': ['p(99) < 100'],
    'grpc_error_rate': ['rate < 0.01'],                                
  },
};

const BASE_LAT = 55.7758;
const BASE_LON = 37.4173;

function getRandomLocalNode() {
  const offsetLat = (Math.random() - 0.5) * 0.02;
  const offsetLon = (Math.random() - 0.5) * 0.02;
  return {
    lat: BASE_LAT + offsetLat,
    lon: BASE_LON + offsetLon,
  };
}

export default () => {
  client.connect('127.0.0.1:54909', { plaintext: true });

  const startNode = getRandomLocalNode();
  const endNode = getRandomLocalNode();

  const snapRes = client.invoke('RoutingService/SnapToNode', startNode);
  check(snapRes, { 'SnapToNode OK': (r) => r && r.status === grpc.StatusOK });

  const routePayload = { 
    from_node: startNode, 
    to_node: endNode 
  };
  const routeRes = client.invoke('RoutingService/GetRoute', routePayload);
  check(routeRes, { 'GetRoute OK': (r) => r && r.status === grpc.StatusOK });

  const boundaryPayload = {
    point: startNode,
    boundaries: [
      { id: 1, lat: BASE_LAT + 0.015, lon: BASE_LON + 0.015 },
      { id: 2, lat: BASE_LAT - 0.015, lon: BASE_LON + 0.015 },
      { id: 3, lat: BASE_LAT, lon: BASE_LON - 0.015 }
    ],
    is_start: true
  };
  
  const boundRes = client.invoke('RoutingService/GetBoundaryConnections', boundaryPayload);
  check(boundRes, { 'BoundaryConnections OK': (r) => r && r.status === grpc.StatusOK });

  client.close();
  
  sleep(0.1); 
};

export function handleSummary(data) {
  console.log(data)
  console.log('Генерация HTML отчета...');
  return {
    "summary.html": htmlReport(data),
  };
}
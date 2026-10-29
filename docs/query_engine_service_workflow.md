# Workflow of QueryEngine Service

our system comprised of distructed microservices that are in DDD & CQRS; 
This is a a workflow of a generic Query Engine that coordinates a list of Domain Query microservice enabling

- DBA to author query to Query Engine
- Client APP can ask domain service to execute authored the query  

## Phase A: Query Authoring

DBA via UI draft/simulate/publish -> QueryEngine <-> QueryService saves nothing

- DBA select a domain from UI loaded  list::domin{name, endpoint} (QueryEngine provides the list, Domain Query Service declare endpoint on boot to be appened to the list)
- `/draft {domainName, queryName, paramTypes}` -> Query Engine saves it
- `/simulate {domainName, queryName, params}` -> Query Engine asks *domain service* /simulate -> forward the response to DBA + mark as simulated
- `/publish {domainName, queryName}`: QueryEngine marks query as published [notice domain query service doesn't need to know about it]

## Phase B: Query Execution

A Client App ask Domain Query Service `/data-query {queryName, params}`

- Domain service asks SQL form Query Engine with `/get-sql {domainName, queryName}`
- For published query, Query Engine  `200 + SQL`  ->  Domain Service Execute -> Client App recieves data list
- For unpublished/unknwon query, Query Engine `409 NOT_PUBLISHED` ->  Domain Service forward `409 NOT_PUBLISHED` -> client shows "Not Found Query"

NOTE: Client App generally has in-app arrangement for query execution that are statically communicated; AI MCP server might have more dynamic needs.
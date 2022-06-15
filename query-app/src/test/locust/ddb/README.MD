# Use Locust.io to run performance test against the AQP

Locust only comes with built-in support for HTTP/HTTPS, but it can be extended to test almost any system. 
In this example, Locust uses Amazon DynamoDB integration, but if you want to implement a different integration  
use [wrapping the protocol library](src/test/locust/ddb/performance_test_ddb_workload.py) and triggering a request 
event after each call has completed, to let Locust know what happened.

## Prerequisite 

### Install the latest version of locust.io with boto3 locally 
```shell script
pip3 install locust boto3
```
### Let's retrieve STS and export AWS_ACCESS_KEY, AWS_SECRET_ACCESS_KEY, and AWS_SESSION_TOKEN into your environment 
Before creating a dynamodb table you need to retrieve AWS credentials  
```shell script
aws sts assume-role --role-arn "arn:aws:iam::123456789012EXAMPLE:role/YourDDBPolicyEXAMPLE" --role-session-name AWSCLI-Sessionexit --duration-seconds 3600
```
### Create the DynamoDB table
```
aws dynamodb create-table \
    --table-name performance_test_table \
    --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
    --provisioned-throughput ReadCapacityUnits=5000,WriteCapacityUnits=5000
```

## Prepare the locust script to generate a workload for Amazon DynamoDB

Let's consider the primary key as `PK: CUSTOMER#<CUSTOMER_NAME>, SK: STATUS#<STATUS>#ORDER#<ORDER_ID>`
with attributes zipcode, username, datetime, status, and total.

### Implement the data generator
The following locust code generates a random workload against the Amazon Keyspaces table

```
...
    @task
    def write_to_ddb(self):
        global operation_name
        operation_name = "write_to_ddb"
        mapValues = generate_map_values()
        partiql_statement_input = create_execute_statement_input("write", mapValues, table_name)
        self.client.execute_statement(dynamodb_client, partiql_statement_input)
...

```   

```json
[
  {
    "zipcode": {
      "N": "56624"
    },
    "datetime": {
      "S": "2021-05-17 06:34:21"
    },
    "amount": {
      "N": "4321.055836431855"
    },
    "sk": {
      "S": "STATUS#PROCESSED#ORDER#701bc582-e60c-11ec-9a2d-acde48001122"
    },
    "username": {
      "S": "user145"
    },
    "pk": {
      "S": "ACCOUNT#ACCOUNT40#CUSTOMER#CUSTOMER33"
    }
  }
]
```

### Start the locust stats aggregator (master)
You need to start one instance of Locust in stats aggregation mode using the --master flag.
 
```shell script
LEADER_HOST=0.0.0.0
SCRIPT_NAME=performance_test_ddb_workload.py
LOCUST_PORT=5557
locust -f $SCRIPT_NAME --master --master-bind-host=$LEADER_HOST --master-bind-port=$LOCUST_PORT --headless -u 10 -r 1 --run-time 5m --stop-timeout 10 &
```

### Start the locust follower (worker) with 10 users, and the spawn rate 1
Now start one or multiple followers using the --worker flag. The test should run 5 minutes with up to 20 requests per second. 

```shell script
locust -f $SCRIPT_NAME --worker --master-host=$LEADER_HOST
```

### The final locust output 

```text
Name                                                          # reqs      # fails  |     Avg     Min     Max  Median  |   req/s failures/s
--------------------------------------------------------------------------------------------------------------------------------------------
 partiQL write_to_ddb                                            5487     0(0.00%)  |      43      27     556      35  |   18.10    0.00
--------------------------------------------------------------------------------------------------------------------------------------------
 Aggregated                                                      5487     0(0.00%)  |      43      27     556      35  |   18.10    0.00

[2022-06-07 17:39:39,265] INFO/locust.main: Shutting down (exit code 0), bye.
[2022-06-07 17:39:39,265] INFO/locust.main: Cleaning up runner...
 Name                                                          # reqs      # fails  |     Avg     Min     Max  Median  |   req/s failures/s
--------------------------------------------------------------------------------------------------------------------------------------------
 partiQL write_to_ddb                                            5487     0(0.00%)  |      43      27     556      35  |   18.29    0.00
--------------------------------------------------------------------------------------------------------------------------------------------
 Aggregated                                                      5487     0(0.00%)  |      43      27     556      35  |   18.29    0.00

Response time percentiles (approximated)
 Type     Name                                                              50%    66%    75%    80%    90%    95%    98%    99%  99.9% 99.99%   100% # reqs
--------|------------------------------------------------------------|---------|------|------|------|------|------|------|------|------|------|------|------|
 partiQL  write_to_ddb                                                       35     37     38     40     49     75    140    250    480    560    560   5487
--------|------------------------------------------------------------|---------|------|------|------|------|------|------|------|------|------|------|------|
 None     Aggregated                                                         35     37     38     40     49     75    140    250    480    560    560   5487
```

## Prepare the locust script to read and aggregate data for Amazon DynamoDB

### Implement data consumer

The data consumer should generate HTTP requests with randomly picked partition keys and bind with the pk parameter.

```roomsql
select zipcode,pk,sum(amount) as total from performance_test_table where pk=? group by zipcode
```

The following locust code will issue a REST request to the AQP

```
    @task
    def aqp_test_group_by_zipcode(self):
        global operation_name
        operation_name = "aqp-requests"
        account_name = "ACCOUNT"+str(randrange(number_accounts))
        customer_name = "CUSTOMER"+str(randrange(number_customers))
        status_code = choice(status_codes)
        # %27 encodes a single quote and %23 encodes hashes in the pk
        pk = "%23".join(["ACCOUNT", account_name, "CUSTOMER", customer_name])
        aggregation_query = "select zipcode,pk,sum(amount) as total from "+table_name+" where pk=%27"+pk+"%27 group by zipcode, pk"
        response = self.client.request(method='GET',name="select zipcode,pk,sum(amount) as total from performance_test_table where pk=? group by zipcode", url="/query-aggregation/"+aggregation_query, auth=(user_name, pw))
```

### The final locust output 

```text
Name                                                          # reqs      # fails  |     Avg     Min     Max  Median  |   req/s failures/s
--------------------------------------------------------------------------------------------------------------------------------------------
 GET select status, pk, count(sk) ...                            979     9(0.92%)  |      68      31     984      54  |    3.26    0.03
--------------------------------------------------------------------------------------------------------------------------------------------
 Aggregated                                                      979     9(0.92%)  |      68      31     984      54  |    3.26    0.03

Response time percentiles (approximated)
 Type     Name                                                              50%    66%    75%    80%    90%    95%    98%    99%  99.9% 99.99%   100% # reqs
--------|------------------------------------------------------------|---------|------|------|------|------|------|------|------|------|------|------|------|
 GET      select status, pk, count(sk) ...                                   54     60     65     69     92    140    230    340    980    980    980    979
--------|------------------------------------------------------------|---------|------|------|------|------|------|------|------|------|------|------|------|
 None     Aggregated                                                         54     60     65     69     92    140    230    340    980    980    980    979

Error report
 # occurrences      Error
--------------------------------------------------------------------------------------------------------------------------------------------
 9                  GET "HTTPError('500 Server Error: Internal Server Error for url: select status, pk, count(sk) as total from performance_test_table where pk=? group by status, pk')"
--------------------------------------------------------------------------------------------------------------------------------------------
```
For a large scale performance testing you might use [docker image](https://hub.docker.com/r/locustio/locust) or [terraform/AWS](https://docs.locust.io/en/stable/running-cloud-integration.html) 